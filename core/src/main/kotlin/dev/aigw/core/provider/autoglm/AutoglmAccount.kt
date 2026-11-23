package dev.aigw.core.provider.autoglm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.str

/**
 * 一个智谱 AutoGLM 账号。
 *
 * 凭证结构对齐本地网关的 internalCredential JSON：
 * `{"accessToken","refreshToken","deviceId","userId","phone"}`。
 * accessToken 是 JWT（短命），refreshToken 用于静默续期；deviceId 必须与登录会话
 * 一致——刷新接口自 2026-11 起强制校验 platform + device_id，每次换随机值会被
 * 上游判为设备漂移。
 */
data class AutoglmAccount(
    val uid: String,
    val accessToken: String = "",
    val refreshToken: String = "",
    val deviceId: String = "",
    val userId: String = "",
    val phone: String = "",
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("uid", uid)
        addProperty("accessToken", accessToken)
        addProperty("refreshToken", refreshToken)
        addProperty("deviceId", deviceId)
        addProperty("userId", userId)
        addProperty("phone", phone)
    }

    fun toSecret(): String = toJson().toString()

    companion object {
        fun parse(raw: String): AutoglmAccount {
            val obj = JsonParser.parseString(raw).asJsonObject
            return AutoglmAccount(
                uid = obj.str("uid"),
                // 兼容 snake_case 老凭证
                accessToken = obj.str("accessToken").ifEmpty { obj.str("access_token") },
                refreshToken = obj.str("refreshToken").ifEmpty { obj.str("refresh_token") },
                deviceId = obj.str("deviceId").ifEmpty { obj.str("device_id") },
                userId = obj.str("userId").ifEmpty { obj.str("user_id") },
                phone = obj.str("phone"),
            )
        }
    }
}
