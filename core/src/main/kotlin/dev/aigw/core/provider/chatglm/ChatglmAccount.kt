package dev.aigw.core.provider.chatglm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.str

/**
 * 一个智谱清言账号。
 *
 * 凭证来自官方 Web 端：cookie `chatglm_token`（访问令牌）+ `chatglm_refresh_token`
 * （双 rotate 刷新令牌）+ localStorage `chatglm-deid`（设备指纹，与浏览器会话绑定）。
 * 上游把 X-Device-Id 与令牌所在会话绑定，随机值会让建会话直接 internal server error。
 */
data class ChatglmAccount(
    val uid: String,
    val token: String = "",
    val refreshToken: String = "",
    val deviceId: String = "",
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("uid", uid)
        addProperty("token", token)
        addProperty("refreshToken", refreshToken)
        addProperty("deviceId", deviceId)
    }

    fun toSecret(): String = toJson().toString()

    fun withTokens(newToken: String, newRefresh: String): ChatglmAccount =
        copy(token = newToken, refreshToken = newRefresh.ifEmpty { refreshToken })

    companion object {
        fun parse(raw: String): ChatglmAccount {
            val obj = JsonParser.parseString(raw).asJsonObject
            return ChatglmAccount(
                uid = obj.str("uid"),
                token = obj.str("token").ifEmpty { obj.str("accessToken") },
                refreshToken = obj.str("refreshToken").ifEmpty { obj.str("refresh_token") },
                deviceId = obj.str("deviceId").ifEmpty { obj.str("deid") },
            )
        }
    }
}
