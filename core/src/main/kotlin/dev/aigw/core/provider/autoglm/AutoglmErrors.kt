package dev.aigw.core.provider.autoglm

import com.google.gson.JsonParser
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.UpstreamError

/**
 * AutoGLM 上游错误分类。
 *
 * 关键区分（本地网关 errorOf 同款）：
 * - 403 绝大多数是「该账号没有这个模型的权益」或「免费额度用完」，不是登录失效；
 *   一律判成会话失效会误禁好账号。
 * - 400 非法模型是模型级问题，不是账号故障，不应计入连续失败。
 */
internal object AutoglmErrors {

    private val QUOTA_MARKERS = listOf(
        "810000", "免费额度已用完", "额度已用完", "额度不足", "开通连续包月", "开通会员",
        "quota exhausted", "insufficient quota", "insufficient_quota",
    )

    private val MODEL_REJECTED_MARKERS = listOf(
        "非法模型", "模型不存在", "不支持该模型",
        "invalid model", "model not found", "illegal model",
    )

    /** 业务码 → 可读文案（本地网关 codeToMsg 同款）。 */
    fun codeMessage(code: Long, msg: String): String = when (code) {
        400001L -> "请求参数有误，请检查手机号与验证码格式" + tail(msg)
        630202L -> "验证码不正确" + tail(msg)
        630101L -> "验证码发送过频，请稍后再试" + tail(msg)
        410000L, 400000L -> "登录态已失效" + tail(msg)
        400002L -> "请求签名校验失败（检查系统时间）" + tail(msg)
        631002L, 630014L -> "需要通过风控验证" + tail(msg)
        else -> "业务码 $code" + if (msg.isEmpty()) "" else "：$msg"
    }

    fun classify(status: Int, body: String): UpstreamError {
        val lower = body.lowercase()
        val message = messageOf(body)

        if (status == 401) {
            return UpstreamError(ErrorKind.SESSION_DEAD, "智谱登录态已失效，请重新登录")
        }
        if (status == 403) {
            if (QUOTA_MARKERS.any { body.contains(it) || lower.contains(it) }) {
                return UpstreamError(
                    ErrorKind.QUOTA,
                    "该模型免费额度已用完，需开通会员继续使用" + if (message.isEmpty()) "" else "：$message",
                )
            }
            return UpstreamError(
                ErrorKind.CLIENT,
                "该账号无权使用此模型或功能（HTTP 403）" + if (message.isEmpty()) "" else "：$message",
            )
        }
        if (status == 429) {
            return UpstreamError(ErrorKind.SOFT_RATE, "智谱限流，请稍后重试")
        }
        if (status == 406) {
            return UpstreamError(ErrorKind.CLIENT, "上游判定提示词不合规（system 闸门）")
        }
        if (status == 402) {
            return UpstreamError(ErrorKind.QUOTA, "智谱额度已用完" + if (message.isEmpty()) "" else "：$message")
        }
        if (status == 400 && MODEL_REJECTED_MARKERS.any { body.contains(it) || lower.contains(it) }) {
            return UpstreamError(ErrorKind.MODEL_LIMIT, "该模型不可用（非法模型）：$message")
        }
        return when {
            status == 0 -> UpstreamError(ErrorKind.NETWORK, "网络连接失败：${body.take(160)}")
            status in 500..599 -> UpstreamError(ErrorKind.SERVER, "智谱上游服务异常（HTTP $status）")
            else -> UpstreamError(ErrorKind.CLIENT, "智谱返回 HTTP $status" + if (message.isEmpty()) "" else "：$message")
        }
    }

    /** 从响应体里取可读消息（信封在 data 或顶层，字段在 message/msg 之间飘）。 */
    fun messageOf(body: String): String {
        if (body.isEmpty()) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return body.trim().take(200)
        for (key in listOf("message", "msg")) {
            val value = obj.str(key)
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    /** HTTP 200 信封：code==0 时返回 data 对象，否则返回 null（原因记日志）。 */
    fun dataOf(body: String): Pair<com.google.gson.JsonObject?, String> {
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return null to "响应不是 JSON：${body.take(160)}"
        val code = root.get("code")?.takeIf { it.isJsonPrimitive }?.asLong ?: -1L
        if (code != 0L) {
            val msg = root.str("msg").ifEmpty { root.str("message") }
            return null to codeMessage(code, msg)
        }
        val data = root.getAsJsonObject("data")
            ?: return null to "响应缺少 data：${body.take(160)}"
        return data to ""
    }

    private fun tail(msg: String): String = if (msg.isEmpty()) "" else "（$msg）"

    private fun com.google.gson.JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
