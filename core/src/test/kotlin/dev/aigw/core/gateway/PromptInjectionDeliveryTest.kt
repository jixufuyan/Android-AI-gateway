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
 * 「system 提示词追加」必须真正发往上游的回归。
 *
 * 背景：中间件链的产物曾经只用于路由判断，真正拼进上游请求体的是**未改写**的原始 body
 * （`withModel(body, …)` 而不是 `withModel(rewritten, …)`）。后果是设置页里填了追加内容、
 * 保存成功、开关也开着，上游收到的 system 里却一个字都没有——从界面完全看不出问题，
 * 只能靠抓包才发现。
 *
 * 这里走完整 HTTP 路径（起真服务器 → 发真请求 → 抓 provider 拿到的请求体），
 * 锁住三件事：
 * 1. 追加文本确实出现在发往上游的第一条 system **末尾**；
 * 2. 客户端原有的 system 内容与 user 消息不被破坏、不丢条；
 * 3. 关闭开关后，上游收到的请求体与客户端原样一致。
 */
class PromptInjectionDeliveryTest {

    /** 记录 openChat 实际收到的 OpenAI body，并回一个最小的非流式成功响应。 */
    private class CaptureProvider(override val id: String = "capture") : Provider {
        override val displayName = "追加投递测试"
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
    fun `追加文本进入发往上游的 system 末尾`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val provider = CaptureProvider()
        engine.registry.register(provider)
        engine.pool.upsert(ProviderAccount("capture", "u1", "账号1", "secret"))

        val appended = "实测紧贴末尾的指令最容易被遵循。"
        engine.updateMiddlewareSettings(
            MiddlewareSettings(systemPromptAppend = appended, systemPromptAppendEnabled = true),
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
        assertTrue(sent.contains(appended), "追加文本没有出现在发往上游的请求体里：$sent")

        val messages = com.google.gson.JsonParser.parseString(sent)
            .asJsonObject.getAsJsonArray("messages")
        assertEquals(2, messages.size(), "原有消息条数不能被改变：$sent")

        val first = messages[0].asJsonObject
        assertEquals("system", first.get("role").asString)
        val firstContent = first.get("content").asString
        assertTrue(firstContent.startsWith("客户端自己的 system"), "追加不能抢到最前：$firstContent")
        assertTrue(firstContent.endsWith(appended), "追加文本必须拼在末尾：$firstContent")
        assertEquals("user", messages[1].asJsonObject.get("role").asString)
    }

    @Test
    fun `客户端没有 system 时新建一条并放在最前`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val provider = CaptureProvider()
        engine.registry.register(provider)
        engine.pool.upsert(ProviderAccount("capture", "u1", "账号1", "secret"))

        engine.updateMiddlewareSettings(
            MiddlewareSettings(systemPromptAppend = "只有追加文本", systemPromptAppendEnabled = true),
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

        val messages = com.google.gson.JsonParser.parseString(provider.lastBody.get())
            .asJsonObject.getAsJsonArray("messages")
        assertEquals(2, messages.size(), "应当新建一条 system 并保留原 user")
        assertEquals("system", messages[0].asJsonObject.get("role").asString)
        assertEquals("只有追加文本", messages[0].asJsonObject.get("content").asString.trim())
        assertEquals("user", messages[1].asJsonObject.get("role").asString)
    }

    @Test
    fun `关闭开关后上游收到与客户端一致的请求体`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val provider = CaptureProvider()
        engine.registry.register(provider)
        engine.pool.upsert(ProviderAccount("capture", "u1", "账号1", "secret"))

        // 开关关掉：内容保留但不追加
        engine.updateMiddlewareSettings(
            MiddlewareSettings(systemPromptAppend = "这段不该出现", systemPromptAppendEnabled = false),
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
        assertTrue(!sent.contains("这段不该出现"), "开关关闭时不应追加：$sent")
        val messages = com.google.gson.JsonParser.parseString(sent)
            .asJsonObject.getAsJsonArray("messages")
        assertEquals(1, messages.size(), "关闭追加后不应新增 system 消息：$sent")
        assertEquals("user", messages[0].asJsonObject.get("role").asString)
    }
}