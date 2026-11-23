package dev.aigw.core.provider.chatglm

import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.UpstreamError

/** 智谱清言错误分类（对齐本地网关 last_error 文案映射）。 */
internal object ChatglmErrors {

    fun classify(status: Int, body: String): UpstreamError {
        val code = codeOf(body)
        // 流内 last_error 与 HTTP 状态共用一套文案
        when (code) {
            ChatglmConstants.CODE_WORKSPACE_NOT_READY -> return UpstreamError(
                ErrorKind.SERVER,
                "workspace 容器未就绪（10030），稍后重试",
                code.toLong(),
            )
            ChatglmConstants.CODE_SERVER_BUSY -> return UpstreamError(
                ErrorKind.SOFT_RATE,
                "服务器被挤爆了，请稍后重试（10024）",
                code.toLong(),
            )
            ChatglmConstants.CODE_NO_CREDITS -> return UpstreamError(
                ErrorKind.QUOTA,
                "剩余积分不足，可购买积分充值包（-1001）",
                code.toLong(),
            )
        }
        return when {
            status == 401 || status == 403 -> UpstreamError(
                ErrorKind.SESSION_DEAD,
                "智谱清言登录态已失效，请重新导入凭证",
            )
            status == 429 -> UpstreamError(ErrorKind.SOFT_RATE, "智谱清言限流，请稍后重试")
            status == 0 -> UpstreamError(ErrorKind.NETWORK, "网络连接失败：${body.take(160)}")
            status in 500..599 -> UpstreamError(
                ErrorKind.SERVER,
                messageOf(body).ifEmpty { "智谱上游服务异常（HTTP $status）" },
            )
            else -> UpstreamError(
                ErrorKind.CLIENT,
                messageOf(body).ifEmpty { "智谱清言拒绝请求（HTTP $status）" },
            )
        }
    }

    /** 从响应体取业务码（顶层 code / status，或 last_error.error_code）。 */
    fun codeOf(body: String): Int {
        if (body.isEmpty()) return 0
        val obj = runCatching {
            com.google.gson.JsonParser.parseString(body).asJsonObject
        }.getOrNull() ?: return 0
        for (key in listOf("code", "status")) {
            val v = obj.get(key)?.takeIf { it.isJsonPrimitive }?.asInt
            if (v != null && v != 0) return v
        }
        val lastError = obj.getAsJsonObject("last_error")
            ?: obj.getAsJsonObject("meta_data")?.getAsJsonObject("last_error")
        return lastError?.get("error_code")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
    }

    fun messageOf(body: String): String {
        if (body.isEmpty()) return ""
        val obj = runCatching {
            com.google.gson.JsonParser.parseString(body).asJsonObject
        }.getOrNull() ?: return body.trim().take(200)
        for (key in listOf("message", "msg", "err_msg")) {
            val v = obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString
            if (!v.isNullOrEmpty()) return v
        }
        return ""
    }
}
