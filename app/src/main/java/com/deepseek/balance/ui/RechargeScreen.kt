package com.deepseek.balance.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.deepseek.balance.network.PaymentClient
import kotlinx.coroutines.delay
import org.json.JSONObject

// 充值页（platform.deepseek.com 的「充值」路由）。未登录时 SPA 会重定向到 /login，
// 登录成功后 SPA 会把令牌写入 localStorage 的 userToken，轮询捕获后回写 App 存储。
private const val TOPUP_URL = "https://platform.deepseek.com/top_up"

/**
 * 支付旁路钩子：包装页面里的 fetch / XMLHttpRequest，旁路监听 SPA 自己发出的
 * 支付请求响应（**不重发任何请求**，clone 响应读取），把订单状态实时记录到
 * window.__dshPay，供 App 每秒读取。监听三类接口（官网前端 JS 包核实）：
 *  ① POST /api/v1/payments              创建订单 → biz_data.payment_order_id / amount
 *  ② POST /api/v1/payments/{id}/capture 查询结果 → biz_data.order.status（SPA 每 5s 轮询）
 *  ③ POST /api/v1/payments/{id}/order_detail 订单详情 → biz_data（兜底补状态）
 *
 * clone 必须在「我们自己的首个 then」里完成（包装后返回新 promise，我们比 SPA 先拿到
 * Response 对象）——若在 SPA 消费之后再 clone，body 已被读锁，clone 会抛异常。
 */
private val PAY_HOOK_JS = """
(function(){
  if (window.__dshPayInstalled) return;
  window.__dshPayInstalled = true;
  window.__dshPay = null;
  function rec(o){
    try {
      var cur = window.__dshPay || {};
      window.__dshPay = {
        orderId: o.orderId || cur.orderId || '',
        status: o.status || cur.status || '',
        amount: (o.amount !== undefined && o.amount !== null && o.amount !== '') ? o.amount : (cur.amount || ''),
        currency: o.currency || cur.currency || '',
        ts: Date.now()
      };
    } catch(e){}
  }
  function biz(t){
    try { var j = JSON.parse(t); return (j && j.data && j.data.biz_data) || null; } catch(e){ return null; }
  }
  function oid(u){ var m = /\/payments\/([^\/\?#]+)/.exec(u); return m ? m[1] : ''; }
  function feed(u, t){
    try {
      u = '' + u;
      if (u.indexOf('/api/v1/payments') === -1) return;
      var bd = biz(t); if (!bd) return;
      if (/\/capture(\?|#|$)/.test(u)) {
        var od = bd.order || {};
        rec({orderId: oid(u), status: od.status || '',
             amount: bd.amount !== undefined ? bd.amount : od.amount,
             currency: bd.currency || od.currency});
      } else if (/\/api\/v1\/payments\/?(\?|#|$)/.test(u)) {
        rec({orderId: bd.payment_order_id || '', status: 'CREATED',
             amount: bd.amount, currency: bd.currency});
      } else if (/\/order_detail(\?|#|$)/.test(u)) {
        var od2 = bd.order || {};
        rec({orderId: oid(u), status: od2.status || '',
             amount: bd.amount !== undefined ? bd.amount : od2.amount,
             currency: bd.currency || od2.currency});
      }
    } catch(e){}
  }
  var of = window.fetch;
  if (of) {
    window.fetch = function(){
      var args = arguments;
      var u = (args[0] && args[0].url) ? ('' + args[0].url) : ('' + args[0]);
      var p = of.apply(this, args);
      if (u.indexOf('/api/v1/payments') === -1) return p;
      return p.then(function(r){
        try { r.clone().text().then(function(t){ feed(u, t); }).catch(function(){}); } catch(e){}
        return r;
      });
    };
  }
  try {
    var ox = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(m, u){ this.__dshU = u; return ox.apply(this, arguments); };
    XMLHttpRequest.prototype.send = function(){
      var x = this;
      try {
        var u = '' + (x.__dshU || '');
        if (u.indexOf('/api/v1/payments') !== -1) {
          x.addEventListener('load', function(){ try { feed(u, x.responseText); } catch(e){} });
        }
      } catch(e){}
      return os.apply(this, arguments);
    };
  } catch(e){}
})()
""".trimIndent()

/** 读取旁路钩子记录的最新订单状态（返回 JSON 字符串，空串 = 尚无订单） */
private val PAY_STATE_JS = """
(function(){
  try { return window.__dshPay ? JSON.stringify(window.__dshPay) : ''; } catch(e) { return ''; }
})()
""".trimIndent()

/** 旁路钩子捕获的支付订单信息 */
private data class PayOrderInfo(
    val orderId: String,
    val amount: String,
    val currency: String,
    val status: String,
)

/** 视为「支付失败/取消」的终态（其余非 SUCCESS 状态一律按等待中继续轮询） */
private val PAY_FAILED_STATUSES = setOf("FAILED", "REFUNDED", "CHARGEBACK", "CLOSED", "EXPIRED", "CANCELLED")

/** 支付宝返回后等待支付结果的确认上限：超时视为未支付，回账单页 */
private const val CONFIRM_TIMEOUT_MS = 180_000L

/** 兜底轮询 capture 的间隔（SPA 自身每 5s 轮询一次，App 错峰 4s） */
private const val CAPTURE_POLL_INTERVAL_MS = 4_000L

/**
 * 全屏 WebView 充值页。与 [WebLoginScreen] 的关键差异：
 * - **不清空会话**：登录页每次打开都清 Cookie/localStorage 以保证看到登录表单；
 *   充值页相反，要保住已有登录态（Cookie 与 localStorage 均保留）。
 * - **恢复登录态**：打开时把 App 已存的网页令牌注入 localStorage（userToken），
 *   SPA 启动时据此恢复登录——手动从 PC 复制过令牌、从未在 App 内登录过的场景也能免登录直达充值页。
 * - **令牌回存**：轮询 localStorage 中的 userToken，检测到新令牌（页面内重新登录/令牌刷新）
 *   即回调 [onTokenUpdated] 持久化，用量查询同步受益。
 * - **支付调起**：页面跳转 `alipays://` / `weixin://` 等协议时交给系统拉起对应 App
 *   （WebView 默认把它们当网页加载，会静默失败 = 点支付宝没反应）。
 * - **充值完成自动检测**（自绘二级「充值成功」页，非 DS 官方账单）：
 *   · JS 旁路钩子监听 SPA 自己的支付轮询响应（微信二维码支付：页面不离开，5s 内感知）；
 *   · App 兜底轮询 capture 接口（支付宝跳 App 返回后页面上下文可能已丢失，只能 App 直接问）；
 *   · 检测到 SUCCESS 弹出「充值成功」（订单号 + 充值金额），点击确定回到 DS 账单界面
 *     （重新加载充值页，页面内展示充值记录），并回调 [onPaymentSuccess] 静默刷新余额。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RechargeScreen(
    webToken: String,
    onTokenUpdated: (String) -> Unit,
    onPaymentSuccess: () -> Unit = {},
    onClose: () -> Unit,
) {
    var isLoading by remember { mutableStateOf(true) }
    var pageTitle by remember { mutableStateOf("充值") }
    var destroyed by remember { mutableStateOf(false) }
    val webViewHolder = remember { mutableStateOf<WebView?>(null) }
    // 是否刚跳出到支付 App：返回（ON_RESUME）时据此确认支付结果
    var jumpedOut by remember { mutableStateOf(false) }

    // ---- 充值完成自动检测状态 ----
    // 旁路钩子/兜底轮询合并出的当前订单信息（页面导航后 Kotlin 侧仍保留，支付宝回跳场景靠它）
    var payOrder by remember { mutableStateOf<PayOrderInfo?>(null) }
    // 已处理过终态提示的订单号（防重复弹窗）
    val shownOrderIds = remember { mutableSetOf<String>() }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var successOrder by remember { mutableStateOf<PayOrderInfo?>(null) }
    // 支付宝返回后正在确认支付结果（页面不刷新，保持订单上下文，顶部悬浮提示）
    var confirmingPayment by remember { mutableStateOf(false) }
    var confirmStartMs by remember { mutableLongStateOf(0L) }
    var lastCapturePollMs by remember { mutableLongStateOf(0L) }

    val hostContext = LocalContext.current

    // 同 WebLoginScreen 的桥接模式：WebViewClient 在 factory 中一次性创建，其回调会固化
    // 首次组合捕获的值；经 rememberUpdatedState 中转才能读到实时的令牌与回调
    val currentWebToken by rememberUpdatedState(webToken)
    val currentOnTokenUpdated by rememberUpdatedState(onTokenUpdated)
    val currentOnPaymentSuccess by rememberUpdatedState(onPaymentSuccess)

    /** 订单状态更新的统一入口：合并（已成功不被 CREATED 降级）+ 终态分发 */
    fun handleOrderUpdate(order: PayOrderInfo) {
        if (order.orderId.isBlank()) return
        val prev = payOrder
        val merged = if (prev != null && prev.orderId == order.orderId &&
            prev.status == PaymentClient.STATUS_SUCCESS &&
            order.status != PaymentClient.STATUS_SUCCESS
        ) {
            prev
        } else {
            order
        }
        payOrder = merged
        if (merged.orderId in shownOrderIds) return
        when (merged.status) {
            PaymentClient.STATUS_SUCCESS -> {
                shownOrderIds.add(merged.orderId)
                successOrder = merged
                showSuccessDialog = true
                confirmingPayment = false
                // 余额已到账：立刻静默刷新，回主界面即见新余额
                currentOnPaymentSuccess()
            }
            in PAY_FAILED_STATUSES -> {
                shownOrderIds.add(merged.orderId)
                confirmingPayment = false
                Toast.makeText(hostContext, "支付未完成或已取消", Toast.LENGTH_SHORT).show()
                // 回到账单界面（充值页展示充值记录），用户可重新发起
                webViewHolder.value?.loadUrl(TOPUP_URL)
            }
        }
    }

    /**
     * 链接分发：http(s)/about/data 等 web 协议继续由 WebView 加载；其余协议
     * （支付宝 alipays://、微信 weixin:// 等）用系统 Intent 拉起对应 App。
     * WebView 默认把 alipays:// 当页面去加载、静默失败——「点支付宝没反应」的根因。
     * intent:// 形态按 Intent URI 解析（清空 component/selector，防 intent 重定向）；
     * 未安装对应 App 时 Toast 说明。返回 true 表示本 URL 已处理，WebView 不要再加载。
     */
    fun launchExternalIfNeeded(view: WebView?, url: String): Boolean {
        if (url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("about:") || url.startsWith("data:") ||
            url.startsWith("blob:") || url.startsWith("javascript:")
        ) return false
        val context = view?.context ?: return true
        jumpedOut = true
        try {
            val intent = if (url.startsWith("intent://")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    // 防 intent 重定向：不允许 intent:// 指定任意组件启动
                    setComponent(null)
                    setSelector(null)
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "未安装对应的支付应用（支付宝/微信）", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "无法调起支付应用", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    fun pollToken() {
        if (destroyed) return
        val wv = webViewHolder.value ?: return
        try {
            wv.evaluateJavascript(TOKEN_PROBE_JS) { raw ->
                val token = raw.parseJsString()
                if (!destroyed && token.isNotBlank() && token != currentWebToken) {
                    currentOnTokenUpdated(token)
                }
            }
        } catch (_: Exception) {
        }
    }

    BackHandler(enabled = true) { destroyed = true; onClose() }

    // 轮询 localStorage 中的 userToken（页面内重新登录 / 令牌刷新时自动回存）
    LaunchedEffect(Unit) {
        while (!destroyed) {
            delay(1000)
            pollToken()
        }
    }

    // 充值完成检测循环（1s 一拍）：
    // ① 读旁路钩子的订单状态（微信二维码支付的主检测：页面不离开，SPA 每 5s 轮询，钩子即时旁听）；
    // ② 兜底轮询 capture 接口（支付宝跳 App 返回后页面可能停在收银台/回跳地址，钩子失效，App 直接问）；
    // ③ 确认等待超时兜底（3 分钟仍 CREATED 视为未支付，回账单页）
    LaunchedEffect(Unit) {
        while (!destroyed) {
            delay(1000)
            val wv = webViewHolder.value ?: continue
            try {
                wv.evaluateJavascript(PAY_STATE_JS) { raw ->
                    if (!destroyed) {
                        parsePayOrderInfo(raw.parseJsString())?.let { handleOrderUpdate(it) }
                    }
                }
            } catch (_: Exception) {
            }
            val order = payOrder
            if (order != null && order.orderId.isNotBlank() &&
                order.status == PaymentClient.STATUS_CREATED && currentWebToken.isNotBlank()
            ) {
                val now = System.currentTimeMillis()
                if (now - lastCapturePollMs >= CAPTURE_POLL_INTERVAL_MS) {
                    lastCapturePollMs = now
                    try {
                        val st = PaymentClient.captureStatus(currentWebToken, order.orderId)
                        if (!destroyed && st.isNotBlank()) {
                            handleOrderUpdate(order.copy(status = st))
                        }
                    } catch (_: Exception) {
                        // 查询失败（网络/令牌）：下一拍重试；主检测仍有页面侧钩子
                    }
                }
            }
            if (confirmingPayment && System.currentTimeMillis() - confirmStartMs > CONFIRM_TIMEOUT_MS) {
                confirmingPayment = false
                Toast.makeText(hostContext, "未检测到支付结果，已返回充值页", Toast.LENGTH_SHORT).show()
                webViewHolder.value?.loadUrl(TOPUP_URL)
            }
        }
    }

    // 从支付 App 返回（ON_RESUME）时的处理：
    // - 有进行中订单（支付宝已调起、状态仍 CREATED）：页面保持在收银台上下文【不刷新】
    //   （刷新会丢掉订单轮询），由钩子/兜底轮询确认结果，成功后自动弹「充值成功」；
    // - 无进行中订单（如只是查看）：原逻辑回充值页刷新订单状态。
    // 普通的前后台切换不受影响（jumpedOut 未置位）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && jumpedOut) {
                jumpedOut = false
                val pending = payOrder
                if (pending != null && pending.orderId.isNotBlank() &&
                    pending.status == PaymentClient.STATUS_CREATED
                ) {
                    confirmingPayment = true
                    confirmStartMs = System.currentTimeMillis()
                } else {
                    isLoading = true
                    webViewHolder.value?.loadUrl(TOPUP_URL)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (pageTitle.isBlank()) "充值" else pageTitle,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { destroyed = true; onClose() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "关闭",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        // AndroidView 的 factory 不是 @Composable 上下文，需先在此处取好主题色
        val webBackground = MaterialTheme.colorScheme.background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewHolder.value = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.setSupportZoom(false)
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        // 去掉 WebView UA 的 "; wv)" 标记：伪装成普通移动 Chrome，
                        // 避免部分收银/支付页对 WebView UA 做差异化处理
                        settings.userAgentString = settings.userAgentString.replace("; wv)", ")")
                        setBackgroundColor(webBackground.toArgb())
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                // 每次导航开始都注入已存令牌：SPA 在启动脚本里读 userToken 恢复登录态，
                                // 注入须赶在 SPA 的 JS 执行前（onPageStarted 是最早的回调时机）
                                val token = currentWebToken
                                if (token.isNotBlank()) {
                                    view?.evaluateJavascript(buildTokenInjectJs(token), null)
                                }
                                // 支付旁路钩子同样要赶在 SPA 发起支付请求前装好（幂等，见 JS 内 guard）
                                view?.evaluateJavascript(PAY_HOOK_JS, null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                view?.evaluateJavascript("document.title") { t ->
                                    pageTitle = t.parseJsString()
                                }
                                // 注入光标修复：落在登录页输入手机号/验证码时绕开 vivo WebView 光标 bug
                                view?.evaluateJavascript(CARET_FIX_JS, null)
                                // 每页加载完兜底检查一次 token
                                pollToken()
                            }

                            // http(s) 页面留在 WebView 内；alipays:// 等支付协议交给系统
                            // 拉起对应 App（见 launchExternalIfNeeded）
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                return launchExternalIfNeeded(view, url)
                            }
                        }
                        // WebChromeClient：使 JS 的 alert/confirm 等对话框可用（收银台页面会用到）
                        webChromeClient = WebChromeClient()
                        loadUrl(TOPUP_URL)
                    }
                },
                onRelease = { view ->
                    destroyed = true
                    view.destroy()
                },
            )

            // 加载遮罩：不透明且盖在最上层，遮挡 WebView 白屏瞬间
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "正在打开充值页…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 支付宝返回后的确认提示：悬浮小胶囊，不拦截触摸（无 clickable，事件穿透到 WebView）
            if (confirmingPayment) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "正在确认支付结果…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }

    // 充值成功二级页：App 自绘（非 DS 官方账单），展示订单号与充值金额；
    // 点击确定回到 DS 账单界面（充值页内展示充值记录）
    if (showSuccessDialog) {
        val order = successOrder
        RechargeSuccessDialog(
            orderId = order?.orderId ?: "",
            amountText = order?.let { formatPayAmount(it.amount, it.currency) } ?: "",
            onConfirm = {
                showSuccessDialog = false
                confirmingPayment = false
                webViewHolder.value?.loadUrl(TOPUP_URL)
            },
        )
    }
}

/**
 * 充值成功二级页：大圆角卡片 + 绿色对勾 + 充值金额（主题色大字）+ 订单号（等宽、可长按复制），
 * 与 App 内「下载更新」弹窗同材质。确定 = 回 DS 账单界面；点弹窗外等价于确定。
 */
@Composable
private fun RechargeSuccessDialog(
    orderId: String,
    amountText: String,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onConfirm) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(48.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "充值成功",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "支付已完成，余额将在片刻后更新",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (amountText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = amountText,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "充值金额",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (orderId.isNotBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(
                                text = "订单号",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            SelectionContainer {
                                Text(
                                    text = orderId,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                FilledTonalButton(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text("确定")
                }
            }
        }
    }
}

/** 解析旁路钩子记录的订单 JSON（amount 兼容数字/字符串两种形态） */
private fun parsePayOrderInfo(json: String): PayOrderInfo? = try {
    if (json.isBlank()) null
    else {
        val o = JSONObject(json)
        PayOrderInfo(
            orderId = o.optString("orderId", ""),
            amount = o.opt("amount")?.toString() ?: "",
            currency = o.optString("currency", ""),
            status = o.optString("status", ""),
        )
    }
} catch (_: Exception) {
    null
}

/** 金额展示：按币种取符号，数值统一两位小数；无法解析时原样拼接 */
private fun formatPayAmount(amount: String, currency: String): String {
    val symbol = when (currency.uppercase()) {
        "USD" -> "$"
        else -> "¥"
    }
    if (amount.isBlank()) return ""
    val v = amount.toDoubleOrNull()
    return if (v != null) symbol + "%.2f".format(java.util.Locale.US, v) else symbol + amount
}

/**
 * 把 App 已存的网页令牌写回 localStorage（userToken），让充值页直接带上登录态。
 * DeepSeek 登录后 SPA 存的是 {"value":"<token>","__version":"0"} 对象，这里按同结构写入；
 * 令牌按 JS 字符串字面量转义（反斜杠/引号/换行），防止拼接串被截断。
 */
private fun buildTokenInjectJs(token: String): String {
    val escaped = token
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\"", "\\\"")
        .replace("\r", "")
        .replace("\n", "")
    return "(function(){try{localStorage.setItem('$TOKEN_KEY', " +
        "JSON.stringify({value:'$escaped',__version:'0'}))}catch(e){}})()"
}
