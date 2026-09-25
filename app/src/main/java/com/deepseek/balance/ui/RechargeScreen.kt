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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

// 充值页（platform.deepseek.com 的「充值」路由）。未登录时 SPA 会重定向到 /login，
// 登录成功后 SPA 会把令牌写入 localStorage 的 userToken，轮询捕获后回写 App 存储。
private const val TOPUP_URL = "https://platform.deepseek.com/top_up"

/**
 * 全屏 WebView 充值页。与 [WebLoginScreen] 的关键差异：
 * - **不清空会话**：登录页每次打开都清 Cookie/localStorage 以保证看到登录表单；
 *   充值页相反，要保住已有登录态（Cookie 与 localStorage 均保留）。
 * - **恢复登录态**：打开时把 App 已存的网页令牌注入 localStorage（userToken），
 *   SPA 启动时据此恢复登录——手动从 PC 复制过令牌、从未在 App 内登录过的场景也能免登录直达充值页。
 * - **令牌回存**：轮询 localStorage 中的 userToken，检测到新令牌（页面内重新登录/令牌刷新）
 *   即回调 [onTokenUpdated] 持久化，用量查询同步受益。
 * - **支付调起**：页面跳转 `alipays://` / `weixin://` 等协议时交给系统拉起对应 App
 *   （WebView 默认把它们当网页加载，会静默失败 = 点支付宝没反应）；从支付 App 返回后
 *   自动回到充值页刷新订单状态。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RechargeScreen(
    webToken: String,
    onTokenUpdated: (String) -> Unit,
    onClose: () -> Unit,
) {
    var isLoading by remember { mutableStateOf(true) }
    var pageTitle by remember { mutableStateOf("充值") }
    var destroyed by remember { mutableStateOf(false) }
    val webViewHolder = remember { mutableStateOf<WebView?>(null) }
    // 是否刚跳出到支付 App：返回（ON_RESUME）时据此回到充值页刷新订单状态
    var jumpedOut by remember { mutableStateOf(false) }

    // 同 WebLoginScreen 的桥接模式：WebViewClient 在 factory 中一次性创建，其回调会固化
    // 首次组合捕获的值；经 rememberUpdatedState 中转才能读到实时的令牌与回调
    val currentWebToken by rememberUpdatedState(webToken)
    val currentOnTokenUpdated by rememberUpdatedState(onTokenUpdated)

    // 本机型 WebView 用 adjustResize 会把页面压缩、焦点输入框被顶出可视区（详见 WebLoginScreen）；
    // 改为 adjustPan，退出充值页时恢复原模式
    val hostContext = LocalContext.current
    DisposableEffect(Unit) {
        val window = (hostContext as? Activity)?.window
        val prevMode = window?.attributes?.softInputMode
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        onDispose {
            if (window != null && prevMode != null) window.setSoftInputMode(prevMode)
        }
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

    BackHandler(enabled = true) { destroyed = true; onClose() }

    // 轮询 localStorage 中的 userToken（页面内重新登录 / 令牌刷新时自动回存）
    LaunchedEffect(Unit) {
        while (!destroyed) {
            delay(1000)
            pollToken()
        }
    }

    // 从支付 App 返回（ON_RESUME）时回到充值页：页面重新拉取订单状态，支付结果立即可见。
    // 普通的前后台切换不受影响（jumpedOut 未置位）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && jumpedOut) {
                jumpedOut = false
                isLoading = true
                webViewHolder.value?.loadUrl(TOPUP_URL)
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
        }
    }
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
