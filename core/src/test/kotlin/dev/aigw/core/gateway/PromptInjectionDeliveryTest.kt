package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「注入提示词」必须真正发往上游的回归。
 *
 * 背景：中间件链的产物曾经只用于路由判断，真正拼进上游请求体的是**未改写**的原始 body
 * （`withModel(body, …)` 而不是 `withModel(rewritten, …)`）。后果是设置页里填了注入提示词、
 * 保存成功、开关也开着，上游收到的 system 里却一个字都没有——从界面完全看不出问题，
 * 只能靠抓包才发现。
 *
 * 这里走完整 HTTP 路径（起真服务器 → 发真请求 → 抓 provider 拿到的请求体），
 * 锁住三件事：
 * 1. 注入文本确实出现在发往上游的 messages[0] 里，且排在最前；
 * 2. 客户端原有的 system 与 user 消息不被破坏、不丢条；
 * 3. 关闭注入开关后，上游收到的请求体与客户端原样一致。
 */
class PromptInjectionDeliveryTest {

    /** 记录 openChat 实际收到的 OpenAI body，并回一个最小的非流式成功响应。 */
    private class CaptureProvider(override val id: String = "capture") : Provider {
        override val displayName = "注入投递测试"
        override val authKind = AuthKind.NONE

        val lastBody = AtomicReference("")

        override fun listModels(account: ProviderAccount?) =
            ProviderModelCatalogView(emptyList(), true, "")

        override fun resolveModel(requested: String) = requested

        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.SERVER, body)

        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
            lastBody.set(openAiBody)
            return AggregatedChatCall(
                200,
                """{"id":"t","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""",
            )
        }

        override fun performAction(
            account: ProviderAccount,
            action: String,
            payload: com.google.gson.JsonObject,
        ): ProviderActionResult = ProviderActionResult.success("ok")
    }

    private fun post(port: Int, body: String): String {
        val conn = URL("http://127.0.0.1:$port/v1/chat/completions")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        assertEquals(200, code, "请求应当成功，实际 $code: $text")
        return text
    }

    @Test
    fun `注入提示词进入发往上游的 system 且排在最前`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val provider = CaptureProvider()
        engine.registry.register(provider)
        engine.pool.upsert(ProviderAccount("capture", "u1", "账号1", "secret"))

        val injected = "你是富江，直接给答案，先动手后解释。"
        engine.updateMiddlewareSettings(
            MiddlewareSettings(systemPromptPrepend = injected, systemPromptPrependEnabled = true),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start()
        try {
            post(
                server.listeningPort,
                """{"model":"capture/m1","messages":[
                   {"role":"system","content":"客户端自己的 system"},
                   {"role":"user","content":"你好"}]}""",
            )
        } finally {
            server.stop()
        }

        val sent = provider.lastBody.get()
        assertTrue(sent.contains(injected), "注入文本没有出现在发往上游的请求体里：$sent")

        val messages = com.google.gson.JsonParser.parseString(sent)
            .asJsonObject.getAsJsonArray("messages")
        assertEquals(2, messages.size(), "原有消息条数不能被改变：$sent")

        val first = messages[0].asJsonObject
        assertEquals("system", first.get("role").asString)
        val firstContent = first.get("content").asString
        assertTrue(firstContent.startsWith(injected), "注入文本必须排在最前：$firstContent")
        assertTrue(firstContent.contains("客户端自己的 system"), "客户端原 system 不能被丢掉")
        assertEquals("user", messages[1].asJsonObject.get("role").asString)
    }

    @Test
    fun `关闭注入开关后上游收到与客户端一致的请求体`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val provider = CaptureProvider()
        engine.registry.register(provider)
        engine.pool.upsert(ProviderAccount("capture", "u1", "账号1", "secret"))

        // 开关关掉：内容保留但不注入
        engine.updateMiddlewareSettings(
            MiddlewareSettings(systemPromptPrepend = "这段不该出现", systemPromptPrependEnabled = false),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start()
        try {
            post(
                server.listeningPort,
                """{"model":"capture/m1","messages":[{"role":"user","content":"你好"}]}""",
            )
        } finally {
            server.stop()
        }

        val sent = provider.lastBody.get()
        assertTrue(!sent.contains("这段不该出现"), "开关关闭时不应注入：$sent")
        val messages = com.google.gson.JsonParser.parseString(sent)
            .asJsonObject.getAsJsonArray("messages")
        assertEquals(1, messages.size(), "关闭注入后不应新增 system 消息：$sent")
        assertEquals("user", messages[0].asJsonObject.get("role").asString)
    }
}