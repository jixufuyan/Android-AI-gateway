package dev.aigw.core.provider.dumate

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * 本地假上游：模拟搭子的两个端点（cookie→token、OpenAI 兼容网关）。
 *
 * 为什么用真实 HTTP 而不是 mock 掉 HTTP 层：这条链路的坑几乎全在协议细节上
 * （HTTP 200 + 业务码、SSE 首块、真实模型响应头、信封层级），把它们替换成
 * 假对象就等于把要验证的东西删掉了。起一个真 socket 才能测到真实行为。
 */
internal class FakeDumateUpstream {

    /** token 接口的行为。 */
    @Volatile var tokenStatus: Int = 200
    @Volatile var tokenBody: String =
        """{"code":0,"result":{"token":"tk-ok","expired_at":"2099-01-01T00:00:00Z"}}"""

    /** 对话接口的行为。 */
    @Volatile var chatStatus: Int = 200
    @Volatile var chatBody: String = ""
    @Volatile var chatContentType: String = "text/event-stream"
    @Volatile var chatRealModel: String = "deepseek-v4.1-flash-low-latency"

    /** 账号信息接口的行为。 */
    @Volatile var userInfoStatus: Int = 200
    @Volatile var userInfoBody: String = """{"code":0,"result":{"uid":"u-123","nickname":"测试号"}}"""

    /** 积分接口。 */
    @Volatile var quotaStatus: Int = 200
    @Volatile var quotaBody: String =
        """{"code":0,"result":{"totalPoints":1000,"usedPoints":250,"isSubscribed":true}}"""

    /** 签到接口。 */
    @Volatile var bonusStatus: Int = 200
    @Volatile var bonusBody: String = """{"code":0,"result":{"points":50}}"""

    /** 记录最近一次对话请求的头与体，用于断言「认证头/请求体有没有原样转发」。 */
    @Volatile var lastChatHeaders: Map<String, String> = emptyMap()

    /** 只让第一次对话失败（验证 401 强刷重试）。 */
    @Volatile var chatFailFirst: Boolean = false
    @Volatile var lastChatBody: String = ""
    @Volatile var chatCallCount: Int = 0
    @Volatile var lastTokenCookie: String = ""

    private var server: HttpServer? = null

    val endpoints: DumateEndpoints
        get() {
            val port = server?.address?.port ?: error("上游未启动")
            return DumateEndpoints(
                webBase = "http://127.0.0.1:$port",
                gatewayBase = "http://127.0.0.1:$port/gateway/apis/v1",
            )
        }

    fun start(): FakeDumateUpstream {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/api/dumate/app/token/get") { ex ->
            lastTokenCookie = ex.requestHeaders.getFirst("Cookie").orEmpty()
            respond(ex, tokenStatus, tokenBody, "application/json")
        }
        s.createContext("/api/dumate/user/info") { ex ->
            respond(ex, userInfoStatus, userInfoBody, "application/json")
        }
        s.createContext("/api/dumate/points/quota_overview") { ex ->
            respond(ex, quotaStatus, quotaBody, "application/json")
        }
        s.createContext("/api/dumate/points/loginBonus") { ex ->
            respond(ex, bonusStatus, bonusBody, "application/json")
        }
        s.createContext("/gateway/apis/v1/chat/completions") { ex ->
            chatCallCount += 1
            lastChatHeaders = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to (v.firstOrNull().orEmpty()) }
            lastChatBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val failNow = chatFailFirst && chatCallCount == 1
            if (chatRealModel.isNotEmpty()) ex.responseHeaders.add(DumateConstants.REAL_MODEL_HEADER, chatRealModel)
            if (failNow) respond(ex, 401, """{"code":10001,"message":"登录态已失效"}""", "application/json")
            else respond(ex, chatStatus, chatBody, chatContentType)
        }
        s.start()
        server = s
        return this
    }

    fun stop() {
        runCatching { server?.stop(0) }
        server = null
    }

    private fun respond(
        ex: com.sun.net.httpserver.HttpExchange,
        status: Int,
        body: String,
        contentType: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        runCatching {
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
        }
        runCatching { ex.close() }
    }
}
