package dev.aigw.core.provider.dumate

import dev.aigw.core.util.HttpSupport
import java.io.InputStream
import java.net.HttpURLConnection

/** 一次普通 JSON 请求的结果；网络层失败也归一成 status=0。 */
internal class DumateJsonResult(val status: Int, val body: String) {
    val isOk: Boolean get() = status in 200..299
}

/** 一次流式请求的连接。非 2xx 时 [stream] 为 null，错误体在 [errorBody]。 */
internal class DumateStreamResult(
    val status: Int,
    val errorBody: String,
    val stream: InputStream?,
    val realModel: String,
    private val connection: HttpURLConnection?,
) {
    val isOk: Boolean get() = status in 200..299 && stream != null

    fun close() {
        runCatching { stream?.close() }
        runCatching { connection?.disconnect() }
    }
}

/**
 * 端点集合。
 *
 * 抽成数据类而不是直接读常量，是为了让测试能指向本地假上游——「换个 URL 就能
 * 端到端跑一遍」，比给每个方法加 if 分支可靠得多。
 */
data class DumateEndpoints(
    val webBase: String = DumateConstants.WEB_BASE,
    val gatewayBase: String = "https://${DumateConstants.GATEWAY_HOST}${DumateConstants.GATEWAY_PREFIX}",
)

/**
 * 搭子的 HTTP 底座。
 *
 * 两条链路的请求头差别很大（网页业务接口要 Cookie + Referer，网关要 Bearer），
 * 所以这里只统一「建连 + 限长读取」这两件与协议无关的事，请求头由调用方逐个注入。
 */
internal class DumateHttp(private val endpoints: DumateEndpoints = DumateEndpoints()) {

    /** 网页业务接口：带 cookie 的同源浏览器请求。 */
    private fun webHeaders(cookie: String, json: Boolean): Map<String, String> = buildMap {
        put("Cookie", cookie)
        put("Accept", "application/json, text/plain, */*")
        put("Accept-Language", "zh-CN,zh;q=0.9")
        // 缺这个头会被上游当成非浏览器来源
        put(DumateConstants.CLIENT_TYPE_HEADER, DumateConstants.CLIENT_TYPE_WEB)
        put("Referer", DumateConstants.WEB_BASE + "/app")
        put("Origin", DumateConstants.WEB_BASE)
        put("User-Agent", DumateConstants.USER_AGENT)
        if (json) put("Content-Type", "application/json")
    }

    /** 云端模型网关：Bearer token。 */
    private fun gatewayHeaders(token: String, stream: Boolean): Map<String, String> = buildMap {
        put("Authorization", "Bearer $token")
        put("Content-Type", "application/json")
        put("Accept", if (stream) "text/event-stream" else "application/json")
        put(DumateConstants.CLIENT_TYPE_HEADER, DumateConstants.CLIENT_TYPE_WEB)
        put(DumateConstants.ACCOUNT_ID_HEADER, DumateConstants.ACCOUNT_ID_GLOBAL)
    }

    /** 打一个网页业务接口。网络异常归一成 status=0，不抛给上层。 */
    fun webJson(
        path: String,
        method: String,
        cookie: String,
        body: String? = null,
        readTimeoutMs: Int = DumateConstants.WEB_READ_TIMEOUT_MS,
    ): DumateJsonResult = webJsonWithStatus(path, method, cookie, body, readTimeoutMs).first

    /**
     * 带 HTTP 状态的版本：排错时要把「上游回了什么」原样打出来，
     * 只拿到 body 分不清「接口 404」和「接口返回了空对象」。
     */
    fun webJsonWithStatus(
        path: String,
        method: String,
        cookie: String,
        body: String? = null,
        readTimeoutMs: Int = DumateConstants.WEB_READ_TIMEOUT_MS,
    ): Pair<DumateJsonResult, Int> {
        val conn = try {
            HttpSupport.open(
                url = endpoints.webBase + path,
                method = method,
                headers = webHeaders(cookie, json = body != null),
                body = body,
                connectTimeoutMs = DumateConstants.CONNECT_TIMEOUT_MS,
                readTimeoutMs = readTimeoutMs,
            )
        } catch (e: Exception) {
            return DumateJsonResult(0, e.message.orEmpty()) to 0
        }
        return try {
            val status = conn.responseCode
            DumateJsonResult(status, HttpSupport.readBody(conn)) to status
        } catch (e: Exception) {
            DumateJsonResult(0, e.message.orEmpty()) to 0
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 打开一次对话请求。
     *
     * 响应头一到就返回，流的读取交给调用方——这是「首字尽早出站」的前提：
     * 先把整条流读进内存再返回，流式就退化成非流式了。
     */
    fun openChat(token: String, body: String, stream: Boolean): DumateStreamResult {
        val conn = try {
            HttpSupport.open(
                url = endpoints.gatewayBase + DumateConstants.PATH_CHAT_COMPLETIONS,
                method = "POST",
                headers = gatewayHeaders(token, stream),
                body = body,
                connectTimeoutMs = DumateConstants.CONNECT_TIMEOUT_MS,
                readTimeoutMs = DumateConstants.CHAT_READ_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            return DumateStreamResult(0, e.message.orEmpty(), null, "", null)
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            runCatching { conn.disconnect() }
            return DumateStreamResult(0, e.message.orEmpty(), null, "", null)
        }
        // 真实后端模型：别名（model-text / glm-5）落到哪个模型只有上游知道，
        // 响应头一到就取走——流结束时连接可能已经读不到头了
        val realModel = runCatching {
            conn.getHeaderField(DumateConstants.REAL_MODEL_HEADER).orEmpty()
        }.getOrDefault("")

        if (status !in 200..299) {
            val errorBody = runCatching { HttpSupport.readBody(conn) }.getOrDefault("")
            runCatching { conn.disconnect() }
            return DumateStreamResult(status, errorBody, null, realModel, null)
        }
        val input = try {
            conn.inputStream
        } catch (e: Exception) {
            runCatching { conn.disconnect() }
            return DumateStreamResult(status, e.message.orEmpty(), null, realModel, null)
        }
        return DumateStreamResult(status, "", input, realModel, conn)
    }
}
