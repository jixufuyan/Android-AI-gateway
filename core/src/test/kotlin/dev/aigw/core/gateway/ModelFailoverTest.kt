package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 同一款模型多供应商故障转移（Failover）自动化回归测试。
 */
class ModelFailoverTest {

    private inner class TestProvider(
        override val id: String,
        private val availableModels: List<String>,
        private val shouldFail: Boolean = false,
        private val responseContent: String = "ok",
    ) : Provider {
        override val displayName = id
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(
            models = availableModels.map { ProviderModel(it, it) },
            fromFallback = false,
            error = "",
        )
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.SERVER, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
            if (shouldFail) {
                return FailedChatCall(500, "上游服务器暂时故障")
            }
            return AggregatedChatCall(
                200,
                """{"id":"c","object":"chat.completion","created":1,"model":"$id","choices":[{"index":0,"message":{"role":"assistant","content":"$responseContent"},"finish_reason":"stop"}]}""",
            )
        }
    }

    @Test
    fun `首选供应商失败时自动故障转移到支持同款模型的备用供应商`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // Provider A: 支持 glm-5.2，但上游崩了 (500)
        val providerA = TestProvider("providerA", listOf("glm-5.2"), shouldFail = true)
        // Provider B: 也支持 glm-5.2，可用
        val providerB = TestProvider("providerB", listOf("glm-5.2"), shouldFail = false, responseContent = "来自B的回复")

        engine.registry.register(providerA)
        engine.registry.register(providerB)

        // 两个供应商都配置账号
        engine.pool.upsert(ProviderAccount("providerA", "u1", "账号A", "{}"))
        engine.pool.upsert(ProviderAccount("providerB", "u2", "账号B", "{}"))

        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)

        try {
            val conn = URL("http://127.0.0.1:${server.listeningPort}/v1/chat/completions")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            // 客户端请求 providerA 的 glm-5.2
            val body = """{"model":"providerA/glm-5.2","messages":[{"role":"user","content":"hi"}]}"""
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))

            assertEquals(200, conn.responseCode, "自动故障转移应成功返回 200")
            val resp = conn.inputStream.bufferedReader().readText()
            assertTrue(resp.contains("来自B的回复"), "响应内容应来自备用供应商 B：$resp")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `首选供应商无可用账号时直接切换到备用供应商`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // Provider A: 支持 gpt-4o，无可用账号
        val providerA = TestProvider("providerA", listOf("gpt-4o"), shouldFail = false)
        // Provider B: 也支持 gpt-4o，有可用账号
        val providerB = TestProvider("providerB", listOf("gpt-4o"), shouldFail = false, responseContent = "来自B的成功响应")

        engine.registry.register(providerA)
        engine.registry.register(providerB)

        // 只有 providerB 配了账号，providerA 没有
        engine.pool.upsert(ProviderAccount("providerB", "u2", "账号B", "{}"))

        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)

        try {
            val conn = URL("http://127.0.0.1:${server.listeningPort}/v1/chat/completions")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = """{"model":"providerA/gpt-4o","messages":[{"role":"user","content":"hi"}]}"""
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))

            assertEquals(200, conn.responseCode, "A 无账号时应自动平滑切到 B")
            val resp = conn.inputStream.bufferedReader().readText()
            assertTrue(resp.contains("来自B的成功响应"), "响应内容：$resp")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `sn- 前缀模型名能路由到 raccoon 并跨供应商找到同款模型`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // raccoon：模型 id 带 sn- 前缀
        val raccoon = TestProvider("raccoon", listOf("sn-deepseek-v4-1-flash-1017"))
        // 另一家：同一款模型但不带前缀
        val other = TestProvider("other", listOf("deepseek-v4-1-flash-1017"))
        engine.registry.register(raccoon)
        engine.registry.register(other)
        engine.pool.upsert(ProviderAccount("raccoon", "u1", "小浣熊", "{}"))
        engine.pool.upsert(ProviderAccount("other", "u2", "别家", "{}"))

        // 1) 无前缀的裸模型名要推给 raccoon，而不是被 deepseek 分支抢给 codebuddy/trae
        val route = engine.resolveRoute("sn-deepseek-v4-1-flash-1017")
        assertEquals("raccoon", route?.providerId, "sn- 开头的模型应归属小浣熊")

        // 2) 候选里必须出现别家的同款模型（剥掉 sn- 后同名），否则限流了永远切不过去
        val candidates = engine.resolveCandidateRoutes("sn-deepseek-v4-1-flash-1017")
        assertTrue(
            candidates.any { it.providerId == "other" && it.model == "deepseek-v4-1-flash-1017" },
            "候选应包含别家的同款模型：$candidates",
        )
    }

    @Test
    fun `五种写法的 glm-5-3-flash 在真实候选列表里互相认成同款`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // 完全按用户给的真实 id 列：小浣熊 / 智谱 / 智谱清言 / WorkBuddy / Loomy
        val ids = listOf(
            "raccoon" to "sn-glm-5-3-flash",
            "autoglm" to "glm-5.3-flash",
            "chatglm" to "glm-5.3-flash",
            "codebuddy" to "glm-5.3-flash",
            "loomy" to "GLM-5.3-Flash",
        )
        ids.forEach { (pid, model) ->
            engine.registry.register(TestProvider(pid, listOf(model)))
            engine.pool.upsert(ProviderAccount(pid, "u-$pid", pid, "{}"))
        }

        // 从小浣熊的写法出发，候选里必须把其余四家全部收进来
        val candidates = engine.resolveCandidateRoutes("raccoon/sn-glm-5-3-flash")
        val hit = candidates.map { it.providerId }.toSet()
        listOf("autoglm", "chatglm", "codebuddy", "loomy").forEach { pid ->
            assertTrue(pid in hit, "$pid 应作为同款模型候选，实际：$candidates")
        }
        // 每家的 upstreamModel 必须是它自己的真实写法，不能把 sn- 原样发过去
        assertEquals("glm-5.3-flash", candidates.first { it.providerId == "autoglm" }.model)
        assertEquals("GLM-5.3-Flash", candidates.first { it.providerId == "loomy" }.model)
    }

    @Test
    fun `不同档位不会被当成同款候选`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(TestProvider("raccoon", listOf("sn-glm-5-3-flash")))
        engine.registry.register(TestProvider("autoglm", listOf("glm-5.3-pro")))
        engine.registry.register(TestProvider("chatglm", listOf("glm-5.3")))
        listOf("raccoon", "autoglm", "chatglm").forEach {
            engine.pool.upsert(ProviderAccount(it, "u-$it", it, "{}"))
        }
        val hit = engine.resolveCandidateRoutes("raccoon/sn-glm-5-3-flash").map { it.providerId }
        assertEquals(listOf("raccoon"), hit, "pro / 无档位版不该被当成 flash 的替代：$hit")
    }

    @Test
    fun `raccoon 业务码额度耗尽时自动切到另一家供应商`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // lucid：模拟小浣熊（HTTP 200 + status.code=500001）
        val faulty = object : Provider {
            override val id = "raccoon"
            override val displayName = "小浣熊"
            override val authKind = AuthKind.NONE
            override fun listModels(account: ProviderAccount?) =
                ProviderModelCatalogView(models = listOf(ProviderModel("sn-deepseek-v4-1-flash-1017", "s")), fromFallback = false, error = "")
            override fun resolveModel(requested: String) = requested
            override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.QUOTA, body)
            override fun openChat(account: ProviderAccount, openAiBody: String) =
                dev.aigw.core.provider.StreamFailureChatCall(
                    200,
                    UpstreamError(ErrorKind.QUOTA, "小浣熊额度/积分不足：quota exceeded"),
                )
        }
        val backup = TestProvider("backup", listOf("sn-deepseek-v4-1-flash-1017"), responseContent = "来自备用的回复")
        engine.registry.register(faulty)
        engine.registry.register(backup)
        engine.pool.upsert(ProviderAccount("raccoon", "u1", "小浣熊", "{}"))
        engine.pool.upsert(ProviderAccount("backup", "u2", "备用", "{}"))

        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)
        try {
            val conn = URL("http://127.0.0.1:${server.listeningPort}/v1/chat/completions")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.write(
                """{"model":"raccoon/sn-deepseek-v4-1-flash-1017","messages":[{"role":"user","content":"hi"}]}"""
                    .toByteArray(Charsets.UTF_8),
            )
            assertEquals(200, conn.responseCode, "额度耗尽应切到备用供应商")
            val resp = conn.inputStream.bufferedReader().readText()
            assertTrue(resp.contains("来自备用的回复"), resp)
        } finally {
            server.stop()
        }
    }
}

