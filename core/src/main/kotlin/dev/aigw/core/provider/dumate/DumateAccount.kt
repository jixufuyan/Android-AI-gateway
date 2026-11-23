package dev.aigw.core.provider.dumate

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.long
import dev.aigw.core.util.str

/**
 * 一个百度搭子（DuMate）网页账号。
 *
 * 唯一的长期凭证是浏览器 cookie（`BDUSS` 等百度登录态）。token 是 cookie 换来的
 * 短期票据（约 1 小时），缓存下来只为了避免每个请求都多一次往返；失效了重新换即可，
 * 换不到才说明 cookie 真的过期了。
 *
 * cookie 里含百度账号的完整登录态，**等同密码**：落盘由上层（Android Keystore
 * 加密存储）负责，这里只做序列化。
 */
data class DumateAccount(
    /** 百度账号 uid；cookie 还没验证过时为空串。 */
    val uid: String,
    /** 完整 cookie 串（原样重放给上游）。 */
    val cookie: String,
    val nickname: String = "",
    /** 缓存的 bearer token。 */
    val token: String = "",
    /** 缓存 token 的过期时间（毫秒）。 */
    val tokenExpiresAtMillis: Long = 0L,
    /** 账号加入时间（毫秒）。 */
    val createdAtMillis: Long = 0L,
) {
    /** token 是否仍可用（留出 [DumateConstants.REFRESH_SKEW_MS] 余量）。 */
    fun tokenUsable(nowMillis: Long): Boolean =
        token.isNotEmpty() &&
            tokenExpiresAtMillis - DumateConstants.REFRESH_SKEW_MS > nowMillis

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("uid", uid)
        addProperty("cookie", cookie)
        addProperty("nickname", nickname)
        addProperty("token", token)
        addProperty("tokenExpiresAt", tokenExpiresAtMillis)
        addProperty("createdAt", createdAtMillis)
    }

    fun toSecret(): String = toJson().toString()

    companion object {
        fun parse(raw: String): DumateAccount {
            val obj = JsonParser.parseString(raw).asJsonObject
            return fromJson(obj)
        }

        fun fromJson(obj: JsonObject): DumateAccount = DumateAccount(
            uid = obj.str("uid"),
            cookie = obj.str("cookie"),
            nickname = obj.str("nickname"),
            token = obj.str("token"),
            tokenExpiresAtMillis = obj.long("tokenExpiresAt"),
            createdAtMillis = obj.long("createdAt"),
        )
    }
}
