package dev.aigw.core.provider.raccoon

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull

/**
 * 小浣熊的错误分类。
 *
 * 上游有**两套**错误形态，此前只认了 HTTP 状态码那一套，于是把第二套全归成
 * `ErrorKind.CLIENT`（「客户端参数错」）：
 *
 * 1. HTTP 非 2xx —— `401/403/429/5xx` 有状态码可判，原来就没问题；
 * 2. **HTTP 200 + 业务码** —— 外层 `{"status":{"code":500001,"message":"quota exceeded"}}`，
 *    额度耗尽、限流、凭证失效都走这条。原来一律 CLIENT，而 CLIENT 在网关侧
 *    对应 `ABORT_IMMEDIATELY`（直接报错、绝不换家），所以「被上游限制了不会自动切换」
 *    就是这么来的。
 *
 * 码值没有公开文档，因此这里「码值优先、消息关键词兜底」，都认不出时才退回 CLIENT。
 */
internal object RaccoonErrors {

    /** 额度/积分耗尽：换号或切换供应商。 */
    private val quotaHints = listOf(
        "quota", "insufficient_quota", "points", "积分", "额度", "余额不足", "no_available_points",
        "out of credit", "credit", "exceeded_your_current_quota", "资源包", "次数已用完", "已用完",
    )

    /** 限流：退避重试或切换供应商。 */
    private val rateHints = listOf(
        "rate limit", "rate_limit", "too many", "频繁", "限流", "frequency", "try again later",
        "please retry", "busy", "系统繁忙", "稍后再试", "qps",
    )

    /** 凭证失效：硬禁用账号，必须重新登录。 */
    private val authHints = listOf(
        "unauthor", "authorization_", "authorization verify", "token", "登录", "未登录",
        "expired", "invalid_user", "verify failed", "凭证", "重新授权", "session",
    )

    /** 该模型不可用（换号无用，只冷却模型并转下一候选供应商）。 */
    private val modelLimitHints = listOf(
        "model not found", "invalid model", "unsupported model", "模型不存在", "模型不可用",
        "no available channel", "no_available_channel", "not available for",
    )

    /** 已知业务码 → 分类（拿到实测值的就写死，没写死的走关键词）。 */
    private fun byCode(code: Long): ErrorKind? = when (code) {
        // authorization_empty_error / authorization_verify_error：没登录或票据被顶掉
        200001L, 200003L -> ErrorKind.SESSION_DEAD
        // quota exceeded（实测值）
        500001L -> ErrorKind.QUOTA
        else -> null
    }

    /**
     * 业务码分类。[message] 是上游原话（可能为空）。
     *
     * 消息保持上游原文不做改写：「额度/限流/登录失效」这类归因由网关按 [ErrorKind]
     * 统一拼在 headline 上（见 GatewayErrorMapping），这里再加前缀会重复两遍。
     */
    fun classifyBusiness(code: Long, message: String): UpstreamError {
        val raw = message.trim()
        val kind = byCode(code) ?: byMessage(raw) ?: ErrorKind.CLIENT
        val text = raw.ifEmpty { "上游业务错误（code=$code）" }
        return UpstreamError(kind, text, code)
    }

    /** HTTP 非 2xx 分类，与业务码分类共用同一套关键词，避免两套口径。 */
    fun classifyHttp(status: Int, body: String): UpstreamError {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        val message = extractMessage(obj, body).ifEmpty { "上游 HTTP $status" }
        val bizCode = obj?.objOrNull("status")?.longOrNull("code")
            ?: obj?.longOrNull("code")
            ?: 0L
        val kind = when {
            status == 401 || status == 403 -> ErrorKind.SESSION_DEAD
            status == 402 -> ErrorKind.QUOTA
            status == 404 -> ErrorKind.NOT_FOUND
            status == 429 -> ErrorKind.SOFT_RATE
            status in 500..599 -> ErrorKind.SERVER
            // 400 也可能携带业务码（如额度耗尽），先看码再看消息，最后才归 CLIENT
            else -> (if (bizCode != 0L) byCode(bizCode) else null)
                ?: byMessage(message)
                ?: ErrorKind.CLIENT
        }
        return UpstreamError(kind, message, bizCode)
    }

    private fun byMessage(message: String): ErrorKind? {
        if (message.isEmpty()) return null
        val lower = message.lowercase()
        fun hit(hints: List<String>) = hints.any { lower.contains(it) }
        return when {
            hit(authHints) -> ErrorKind.SESSION_DEAD
            hit(quotaHints) -> ErrorKind.QUOTA
            hit(rateHints) -> ErrorKind.SOFT_RATE
            hit(modelLimitHints) -> ErrorKind.MODEL_LIMIT
            else -> null
        }
    }

    private fun extractMessage(obj: JsonObject?, body: String): String {
        if (obj == null) return ""
        // 外层 status.message 是小浣熊的主口径；error.message 是 LiteLLM 兼容层
        obj.objOrNull("status")?.stringOrNull("message")?.takeIf { it.isNotEmpty() }?.let { return it }
        obj.objOrNull("error")?.stringOrNull("message")?.takeIf { it.isNotEmpty() }?.let { return it }
        for (key in listOf("message", "msg", "details", "detail")) {
            obj.stringOrNull(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return body.trim().take(200).takeIf { it.isNotEmpty() && !it.startsWith("{") } ?: ""
    }
}
