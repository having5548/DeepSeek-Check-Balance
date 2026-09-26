package com.deepseek.balance.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 充值订单接口客户端（platform.deepseek.com/api/v1/payments，网页令牌鉴权）。
 *
 * 充值流程（已从官网前端 JS 包核实，SPA 同款）：
 *  ① POST /api/v1/payments               创建订单 → biz_data: {payment_order_id, amount, payment_session_data}
 *  ② POST /api/v1/payments/{id}/capture  查询支付结果 → biz_data: {order: {status}, retry_required}
 *     status ∈ {CREATED, PENDING, SUCCESS, FAILED, REFUNDED, CHARGEBACK}；
 *     网页在等待支付期间每 5s 轮询一次 capture，直到状态终态。
 *
 * App 侧不创建订单：主检测是注入页面的 JS 钩子旁路监听 SPA 自己的轮询响应（RechargeScreen）；
 * 本类仅在「已知进行中订单号」时兜底轮询 capture——支付宝跳 App 返回后页面上下文可能
 * 已不在 platform.deepseek.com（被收银台/回跳地址覆盖），钩子失效，此时只能靠 App 直接问。
 */
object PaymentClient {

    private const val BASE_URL = "https://platform.deepseek.com"

    /** 订单已创建 / 处理中（等待支付） */
    const val STATUS_CREATED = "CREATED"
    const val STATUS_PENDING = "PENDING"

    /** 支付成功 */
    const val STATUS_SUCCESS = "SUCCESS"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 查询订单当前支付状态（capture）。返回状态字符串（如 "SUCCESS"）；
     * 业务层拿不到状态（biz_code 非 0 等）返回空串由调用方继续轮询；
     * 网络/鉴权失败抛 [ApiException]（401/403/40003 = 网页令牌失效）。
     */
    suspend fun captureStatus(token: String, orderId: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/api/v1/payments/$orderId/capture")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            )
            .header("Referer", "https://platform.deepseek.com/top_up")
            .header("x-app-version", "1.0.0")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw ApiException("响应体为空")
        if (!response.isSuccessful) {
            throw ApiException(
                message = when (response.code) {
                    401 -> "网页令牌无效或已过期，请重新获取"
                    403 -> "网页令牌无权访问"
                    429 -> "请求过于频繁，请稍后再试"
                    else -> "支付结果查询失败 (${response.code})"
                },
                code = response.code,
            )
        }
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw ApiException("响应解析失败: ${e.message}")
        }
        // 网页接口统一信封：{code, data: {biz_code, biz_data}}；鉴权失败业务码 40003
        if (root.optInt("code", -1) == 40003) {
            throw ApiException("网页令牌无效或已过期，请重新获取", code = 40003)
        }
        val data = root.optJSONObject("data") ?: return@withContext ""
        if (data.optInt("biz_code", 0) != 0) return@withContext ""
        val bizData = data.optJSONObject("biz_data") ?: return@withContext ""
        bizData.optJSONObject("order")?.optString("status", "") ?: ""
    }
}
