package dev.aigw.core.provider.autoglm

import dev.aigw.core.util.HttpSupport
import java.io.InputStream
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.UUID

/** 一次普通 JSON 请求的结果；网络层失败也归一成 status=0。 */
internal class AutoglmJsonResult(val status: Int, val body: String) {
    val isOk: Boolean get() = status in 200..299
}

/** 一次流式对话请求的连接。非 2xx 时 [stream] 为 null，错误体在 [errorBody]。 */
internal class AutoglmStreamResult(
    val status: Int,
    val errorBody: String,
    val stream: InputStream?,
    private val connection: HttpURLConnection?,
) {
    val isOk: Boolean get() = status in 200..299 && stream != null

    fun close() {
        runCatching { stream?.close() }
        runCatching { connection?.disconnect() }
    }
}

/**
 * AutoGLM 的 HTTP 底座：请求头的组装（对话头 vs 签名头）与建连分开。
 *
 * 两套头差异很大（对话走 X-Authorization + X-Request-Model，签名 API 走
 * X-Auth-Sign 三件套 + authorization），错用一套会被上游直接拒，所以拆成两个方法。
 */
internal object AutoglmHttp {

    /** md5hex（本地网关 EndpointDiscovery.md5Hex 同款实现）。 */
    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 对话请求头（本地网关 chatHeaders 同款；注意认证头是 X-Authorization）。 */
    fun chatHeaders(accessToken: String, routeId: String): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("Accept", "*/*")
        put("User-Agent", AutoglmConstants.DESKTOP_UA)
        put("X-Product", "autoclaw")
        put("X-Client-Type", "pc")
        put("X-Tm", "win")
        put("X-Version", "1.17.8")
        put("X-Lang", "zh-CN")
        put("X-Channel", "official")
        put("x_trace_id", "autoclaw-desktop")
        put("X-Authorization", "Bearer $accessToken")
        put("X-Request-Id", UUID.randomUUID().toString())
        put("X-Request-Model", routeId)
    }

    /** 签名 API 头（模型/钱包/刷新/签到；本地网关 signHeaders 同款）。 */
    fun signHeaders(accessToken: String): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("Accept", "*/*")
        put("X-Product", "autoclaw")
        put("X-Client-Type", "pc")
        put("X-Tm", "win")
        put("X-Version", "1.18.5")
        put("X-Lang", "zh-CN")
        put("X-Channel", "official")
        put("X-Harness-Type", "zcode")
        put("X-Auth-Appid", AutoglmConstants.SIGN_APPID)
        val ts = (System.currentTimeMillis() / 1000L).toString()
        put("X-Auth-TimeStamp", ts)
        put("X-Auth-Sign", md5Hex("${AutoglmConstants.SIGN_APPID}&$ts&${AutoglmConstants.SIGN_KEY}"))
        put("X-Trace-Id", UUID.randomUUID().toString())
        if (accessToken.isNotEmpty()) put("authorization", "Bearer $accessToken")
    }

    /** 打一个签名/登录类 JSON 接口。网络异常归一成 status=0，不抛给上层。 */
    fun json(
        path: String,
        method: String,
        accessToken: String,
        body: String? = null,
        query: String = "",
        readTimeoutMs: Int = AutoglmConstants.JSON_READ_TIMEOUT_MS,
    ): AutoglmJsonResult {
        val conn = try {
            HttpSupport.open(
                url = AutoglmConstants.BASE + path + query,
                method = method,
                headers = signHeaders(accessToken),
                body = body,
                connectTimeoutMs = AutoglmConstants.CONNECT_TIMEOUT_MS,
                readTimeoutMs = readTimeoutMs,
            )
        } catch (e: Exception) {
            return AutoglmJsonResult(0, e.message.orEmpty())
        }
        return try {
            AutoglmJsonResult(conn.responseCode, HttpSupport.readBody(conn))
        } catch (e: Exception) {
            AutoglmJsonResult(0, e.message.orEmpty())
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 打开一次对话请求。响应头一到就返回，流的读取交给调用方——
     * 先整条读进内存再返回会把流式退化成非流式。
     */
    fun openChat(accessToken: String, routeId: String, body: String): AutoglmStreamResult {
        val conn = try {
            HttpSupport.open(
                url = AutoglmConstants.BASE + AutoglmConstants.PATH_CHAT,
                method = "POST",
                headers = chatHeaders(accessToken, routeId),
                body = body,
                connectTimeoutMs = AutoglmConstants.CONNECT_TIMEOUT_MS,
                readTimeoutMs = AutoglmConstants.CHAT_READ_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            return AutoglmStreamResult(0, e.message.orEmpty(), null, null)
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            runCatching { conn.disconnect() }
            return AutoglmStreamResult(0, e.message.orEmpty(), null, null)
        }
        if (status !in 200..299) {
            val errorBody = runCatching { HttpSupport.readBody(conn) }.getOrDefault("")
            runCatching { conn.disconnect() }
            return AutoglmStreamResult(status, errorBody, null, null)
        }
        val input = try {
            conn.inputStream
        } catch (e: Exception) {
            runCatching { conn.disconnect() }
            return AutoglmStreamResult(status, e.message.orEmpty(), null, null)
        }
        return AutoglmStreamResult(status, "", input, conn)
    }
}
