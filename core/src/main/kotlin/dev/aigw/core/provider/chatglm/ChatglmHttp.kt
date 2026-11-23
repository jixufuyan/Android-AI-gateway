package dev.aigw.core.provider.chatglm

import com.google.gson.JsonParser
import dev.aigw.core.util.HttpSupport
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/** 一次普通 JSON 请求的结果；网络层失败也归一成 status=0。 */
internal class ChatglmJsonResult(val status: Int, val body: String) {
    val isOk: Boolean get() = status in 200..299
}

/** 一次流式请求的连接。非 2xx 时 [stream] 为 null，错误体在 [errorBody]。 */
internal class ChatglmStreamResult(
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
 * 智谱清言签名 HTTP 底座。
 *
 * 关键点（对齐官方 Web 端与 1.5.9 参考实现）：
 * - X-Timestamp 先用服务器时间校准（cur_ts），再对倒数第二位做「数字和校验位替换」；
 * - X-Sign = md5(X-Timestamp + "-" + X-Nonce + "-" + 固定盐)；
 * - X-Request-Id / X-Nonce / X-Sign 是**一次性**字段：重连必须重新签发，
 *   复用旧头等于重放 nonce，上游必判 400/40014；
 * - Content-Type 必须是 `application/json;charset=utf-8`（无空格），上游做指纹校验。
 */
internal class ChatglmHttp(private val account: ChatglmAccount) {

    private var tsOffset = 0L
    private var tsSynced = false
    private val tsLock = Any()

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 服务器时间校准：首请求拉 cur_ts 计算偏移并缓存。 */
    private fun nowMillis(): Long {
        if (!tsSynced) {
            synchronized(tsLock) {
                if (!tsSynced) {
                    try {
                        val t0 = System.currentTimeMillis()
                        val conn = (URL(ChatglmConstants.BASE + ChatglmConstants.PATH_CUR_TS)
                            .openConnection() as HttpURLConnection).apply {
                            connectTimeout = 3_000
                            readTimeout = 3_000
                            setRequestProperty("User-Agent", ChatglmConstants.UA)
                        }
                        val body = HttpSupport.readLimited(conn.inputStream, 4_096)
                        runCatching { conn.disconnect() }
                        // 对齐本地网关 EndpointDiscovery.chatglmNow：result 是对象，
                        // 时间戳在 result.timestamp_ms；之前直接把 result 当 long 取，
                        // 取不到就静默退回本机时间，签名时间窗全靠运气。
                        val server = runCatching {
                            val root = JsonParser.parseString(body).asJsonObject
                            val r = root.get("result")
                            if (r != null && r.isJsonObject) {
                                r.asJsonObject.get("timestamp_ms")
                                    ?.takeIf { it.isJsonPrimitive }?.asLong
                            } else {
                                r?.takeIf { it.isJsonPrimitive }?.asLong
                            }
                        }.getOrNull() ?: 0L
                        if (server > 0) {
                            tsOffset = server + (System.currentTimeMillis() - t0) / 2 -
                                System.currentTimeMillis()
                        }
                    } catch (_: Exception) {
                    } finally {
                        tsSynced = true
                    }
                }
            }
        }
        return System.currentTimeMillis() + tsOffset
    }

    /** 签名头（每次调用都产出全新的一次性字段）。 */
    fun signedHeaders(bearer: String?): Map<String, String> {
        var ts = nowMillis().toString()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        if (ts.length >= 3) {
            var sum = 0
            for (c in ts) sum += c - '0'
            val check = (sum - (ts[ts.length - 2] - '0')).mod(10)
            ts = ts.substring(0, ts.length - 2) + check + ts.substring(ts.length - 1)
        }
        return buildMap {
            put("Content-Type", "application/json;charset=utf-8")
            put("App-Name", "chatglm")
            // X-Device-Id 必须是登录时的真实 chatglm-deid：上游拿它与令牌所在
            // 浏览器会话绑定；空时兜底随机 UUID 去横线（参考实现从不发空值）
            put(
                "X-Device-Id",
                account.deviceId.ifEmpty { UUID.randomUUID().toString().replace("-", "") },
            )
            put("X-Request-Id", UUID.randomUUID().toString().replace("-", ""))
            put("X-App-Platform", "pc")
            put("X-App-Version", "0.0.1")
            put("X-Timestamp", ts)
            put("X-Nonce", nonce)
            put("X-Sign", md5Hex("$ts-$nonce-${ChatglmConstants.SIGN_KEY}"))
            put("User-Agent", ChatglmConstants.UA)
            if (!bearer.isNullOrEmpty()) put("Authorization", "Bearer $bearer")
        }
    }

    /** 打一个签名 JSON 接口。网络异常归一成 status=0，不抛给上层。 */
    fun json(
        path: String,
        method: String,
        bearer: String? = account.token,
        body: String? = null,
    ): ChatglmJsonResult {
        val conn = try {
            HttpSupport.open(
                url = ChatglmConstants.BASE + path,
                method = method,
                headers = signedHeaders(bearer),
                body = body,
                connectTimeoutMs = ChatglmConstants.CONNECT_TIMEOUT_MS,
                readTimeoutMs = ChatglmConstants.JSON_READ_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            return ChatglmJsonResult(0, e.message.orEmpty())
        }
        return try {
            ChatglmJsonResult(conn.responseCode, HttpSupport.readBody(conn))
        } catch (e: Exception) {
            ChatglmJsonResult(0, e.message.orEmpty())
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 打开一次 stream 请求（每次重新签发头）。响应头一到就返回。 */
    fun openStream(bearer: String?, payload: String): ChatglmStreamResult {
        val headers = signedHeaders(bearer).toMutableMap().apply {
            // 参考实现的 stream 请求：Accept 重复两遍是官方指纹的一部分
            put("Accept", "text/event-stream, text/event-stream")
        }
        val conn = try {
            HttpSupport.open(
                url = ChatglmConstants.BASE + ChatglmConstants.PATH_STREAM,
                method = "POST",
                headers = headers,
                body = payload,
                connectTimeoutMs = ChatglmConstants.CONNECT_TIMEOUT_MS,
                readTimeoutMs = ChatglmConstants.CHAT_READ_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            return ChatglmStreamResult(0, e.message.orEmpty(), null, null)
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            runCatching { conn.disconnect() }
            return ChatglmStreamResult(0, e.message.orEmpty(), null, null)
        }
        if (status !in 200..299) {
            val errorBody = runCatching { HttpSupport.readBody(conn) }.getOrDefault("")
            runCatching { conn.disconnect() }
            return ChatglmStreamResult(status, errorBody, null, null)
        }
        val input = try {
            conn.inputStream
        } catch (e: Exception) {
            runCatching { conn.disconnect() }
            return ChatglmStreamResult(status, e.message.orEmpty(), null, null)
        }
        return ChatglmStreamResult(status, "", input, conn)
    }
}
