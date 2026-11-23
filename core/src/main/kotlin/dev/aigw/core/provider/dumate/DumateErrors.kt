package dev.aigw.core.provider.dumate

import com.google.gson.JsonParser
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str

/**
 * 搭子上游错误分类。
 *
 * 网页业务接口的失效信号**不只是 401**：上游对登录态过期会回 HTTP 200 + `code=10001`，
 * 只看状态码会把过期账号判成普通业务错误——既不禁用也不提示重登，问题被静默吃掉。
 * 所以这里统一按「先业务码、后状态码」的顺序判定。
 */
internal object DumateErrors {

    /** 登录态失效的文案信号（上游不总给业务码）。 */
    private val SESSION_DEAD_MARKERS = listOf(
        "登录态已失效", "登录已失效", "未登录", "请重新登录",
        "session expired", "not logged in", "unauthorized",
    )

    /** 积分/额度耗尽的文案信号。 */
    private val QUOTA_MARKERS = listOf(
        "积分不足", "积分已用完", "余额不足", "额度不足", "额度已用完",
        "insufficient", "quota exceeded", "points not enough",
    )

    /** 从响应体里取业务 code；取不到返回 null。 */
    fun codeOf(body: String): Long? {
        if (body.isEmpty()) return null
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        val raw = obj.get("code") ?: return null
        if (!raw.isJsonPrimitive) return null
        return runCatching { raw.asLong }.getOrNull()
    }

    /** 业务 code 是否代表登录态失效。 */
    fun isSessionDeadCode(code: Long?): Boolean =
        code == DumateConstants.CODE_SESSION_DEAD || code == DumateConstants.CODE_SESSION_DEAD_ALT

    /** 分类：返回 [ErrorKind] 与给用户看的一句话。 */
    fun classify(status: Int, body: String): Pair<ErrorKind, String> {
        val code = codeOf(body)
        val text = body.lowercase()

        if (isSessionDeadCode(code) || SESSION_DEAD_MARKERS.any { text.contains(it) || body.contains(it) }) {
            return ErrorKind.SESSION_DEAD to "百度搭子登录态已失效，需要重新登录（重新复制一次 cookie）"
        }
        if (QUOTA_MARKERS.any { text.contains(it) }) {
            return ErrorKind.QUOTA to "搭子积分已耗尽，去官网签到或换账号"
        }
        // 401 是明确的凭证失效；**403 不是**——云网关对「模型无权访问 / 参数越界」
        // 也回 403。把 403 一律判成会话失效会在 onAccountError 里直接禁用账号，
        // 一个参数错误就把好账号打死，必须重登才能恢复。
        if (status == 401) {
            return ErrorKind.SESSION_DEAD to "百度搭子登录态已失效，需要重新登录"
        }
        if (status == 403) {
            return ErrorKind.CLIENT to (messageOf(body).ifEmpty { "搭子拒绝访问（HTTP 403，可能是模型未授权或参数越界）" })
        }

        val message = messageOf(body)
        return when {
            status == 404 -> ErrorKind.NOT_FOUND to (message.ifEmpty { "上游接口不存在（HTTP 404）" })
            status == 429 -> ErrorKind.SOFT_RATE to (message.ifEmpty { "请求过于频繁，已被上游限流" })
            status in 500..599 -> ErrorKind.SERVER to (message.ifEmpty { "搭子上游服务异常（HTTP $status）" })
            status == 402 -> ErrorKind.QUOTA to (message.ifEmpty { "搭子积分已耗尽" })
            else -> ErrorKind.CLIENT to (message.ifEmpty { "搭子拒绝了本次请求（HTTP $status）" })
        }
    }

    /**
     * 从响应体里取可读消息。
     *
     * 搭子的信封不统一：有的包在 `result`/`data`，有的直接顶层；
     * 字段名在 `message`/`msg`/`error_message` 之间飘。
     */
    fun messageOf(body: String): String {
        if (body.isEmpty()) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return body.trim().take(200)
        for (key in listOf("message", "msg", "error_message", "error")) {
            val value = obj.str(key)
            if (value.isNotEmpty()) return value
        }
        for (wrapper in listOf("result", "data")) {
            val inner = obj.objOrNull(wrapper) ?: continue
            for (key in listOf("message", "msg", "error_message")) {
                val value = inner.str(key)
                if (value.isNotEmpty()) return value
            }
        }
        return ""
    }

    /** 上游 HTTP 状态码 → 对外的 HTTP 状态码（业务码优先于状态码）。 */
    fun httpStatusOf(status: Int, kind: ErrorKind): Int = when (kind) {
        ErrorKind.SESSION_DEAD -> 401
        ErrorKind.QUOTA -> 402
        ErrorKind.SOFT_RATE -> 429
        ErrorKind.NOT_FOUND -> 404
        ErrorKind.SERVER -> 502
        ErrorKind.NETWORK -> 502
        else -> if (status in 400..499) status else 400
    }
}
