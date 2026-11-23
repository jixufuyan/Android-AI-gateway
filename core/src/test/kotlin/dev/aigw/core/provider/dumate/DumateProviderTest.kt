package dev.aigw.core.provider.dumate

import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.UpstreamError
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 搭子通道的端到端行为验证（打本地真 socket，不是 mock 掉 HTTP）。
 *
 * 覆盖的是这条链路上真正会出错的地方：
 * - cookie → token → 网关 三段串联，认证头有没有带对
 * - HTTP 200 + 业务码 10001 这类「看着成功其实失败」的响应
 * - 流式透传（首块不能被探测读掉）与非流式聚合
 * - token 过期时强刷一次重试，而不是直接判死
 */
class DumateProviderTest {

    private lateinit var upstream: FakeDumateUpstream
    private lateinit var provider: DumateProvider
    private val accountErrors = mutableListOf<UpstreamError>()

    private val sseBody = buildString {
        append("""data: {"id":"c1","model":"deepseek-v4.1-flash-low-latency","choices":[{"index":0,"delta":{"content":"你"},"finish_reason":null}]}""")
        append("\n\n")
        append("""data: {"id":"c1","choices":[{"index":0,"delta":{"content":"好"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""")
        append("\n\n")
        append("data: [DONE]\n\n")
    }

    @BeforeTest
    fun setUp() {
        upstream = FakeDumateUpstream().start()
        provider = DumateProvider(
            hooks = ProviderHooks(
                onAccountError = { _, _, error -> accountErrors.add(error) },
            ),
            endpoints = upstream.endpoints,
        )
    }

    @AfterTest
    fun tearDown() {
        upstream.stop()
    }

    /** 造一个已导入的账号（走真实导入路径，顺带验证 cookie 规范化与 uid 解析）。 */
    private fun importAccount(cookie: String = "BDUSS=abc; STOKEN=def"): ProviderAccount =
        assertNotNull(provider.importCredentials(cookie), "导入应成功")

    @Test
    fun `导入时 cookie 换到 token 并解析出 uid 与昵称`() {
        val account = importAccount()
        assertEquals("u-123", account.uid)
        assertEquals("测试号", account.nickname)
        assertEquals("BDUSS=abc; STOKEN=def", upstream.lastTokenCookie, "cookie 应原样带给 token 接口")

        val secret = DumateAccount.parse(account.secret)
        assertEquals("tk-ok", secret.token)
        assertTrue(secret.tokenExpiresAtMillis > 0, "过期时间应被解析出来")
    }

    @Test
    fun `cookie 失效时导入直接报错而不是塞一条死账号`() {
        upstream.tokenStatus = 401
        upstream.tokenBody = """{"code":10001,"message":"登录态已失效"}"""
        val error = runCatching { provider.importCredentials("BDUSS=dead") }.exceptionOrNull()
        assertNotNull(error, "cookie 无效时导入必须失败")
        assertTrue(
            error.message.orEmpty().contains("失效"),
            "错误信息要能看出是登录态问题，实际：${error.message}",
        )
    }

    @Test
    fun `流式请求原样透传，首块不丢`() {
        upstream.chatBody = sseBody
        val account = importAccount()
        val call = provider.openChat(
            account,
            """{"model":"model-text","stream":true,"messages":[{"role":"user","content":"hi"}]}""",
        )
        assertEquals(200, call.status)
        val text = call.stream!!.use { it.readBytes().toString(Charsets.UTF_8) }
        // 首块被探测读掉是这类实现最常见的 bug：客户端会莫名少第一个字
        assertTrue(text.startsWith("data: "), "流的第一段必须是完整的 data 帧，实际开头：${text.take(20)}")
        assertTrue(text.contains("\"你\""), "正文内容应在流里")
        assertTrue(text.trimEnd().endsWith("[DONE]"), "流必须以 [DONE] 收尾")
    }

    @Test
    fun `非流式请求时把上游 SSE 聚合成本地 JSON`() {
        upstream.chatBody = sseBody
        val account = importAccount()
        val call = provider.openChat(
            account,
            """{"model":"model-text","stream":false,"messages":[{"role":"user","content":"hi"}]}""",
        )
        assertEquals(200, call.status)
        val json = assertNotNull(call.aggregated, "非流式请求应返回聚合后的 JSON")
        assertTrue(json.contains("你好"), "聚合结果应包含完整正文，实际：$json")
        assertTrue(json.contains("\"usage\""), "聚合结果应保留 usage")
    }

    @Test
    fun `请求头带 Bearer token 与浏览器标记`() {
        upstream.chatBody = sseBody
        val account = importAccount()
        provider.openChat(account, """{"model":"model-text","stream":true,"messages":[]}""").close()

        val auth = upstream.lastChatHeaders["authorization"].orEmpty()
        assertEquals("Bearer tk-ok", auth, "必须带 Bearer token")
        assertEquals(
            "web",
            upstream.lastChatHeaders[DumateConstants.CLIENT_TYPE_HEADER.lowercase()],
            "缺这个头上游会当成非浏览器来源",
        )
    }

    @Test
    fun `HTTP 200 但业务码 10001 视为登录态失效并上报账号`() {
        upstream.chatBody = """{"code":10001,"message":"登录态已失效"}"""
        upstream.chatContentType = "application/json"
        val account = importAccount()
        val call = provider.openChat(account, """{"model":"model-text","stream":true,"messages":[]}""")

        val failure = assertNotNull(call.failure, "200 + 业务错误码必须归类为失败")
        assertEquals(ErrorKind.SESSION_DEAD, failure.kind)
        assertTrue(accountErrors.any { it.kind == ErrorKind.SESSION_DEAD }, "应通知账号池禁用该账号")
    }

    @Test
    fun `token 被上游作废时强刷一次并重试成功`() {
        upstream.chatBody = sseBody
        // 第一次对话回 401（模拟「本地没过期但服务端已作废」），之后正常
        upstream.chatFailFirst = true
        val account = importAccount()

        val call = provider.openChat(account, """{"model":"model-text","stream":true,"messages":[]}""")
        assertEquals(200, call.status, "强刷 token 后应重试成功，而不是直接把 401 抛给客户端")
        assertEquals(2, upstream.chatCallCount, "应该正好重试一次（不是零次也不是无限次）")
        val text = call.stream!!.use { it.readBytes().toString(Charsets.UTF_8) }
        assertTrue(text.contains("[DONE]"))
    }

    @Test
    fun `积分与签到走网页接口`() {
        val account = importAccount()
        val credit = assertNotNull(provider.creditInfo(account))
        assertTrue(credit.known)
        assertEquals(750L, credit.balance, "余额应为 total - used")

        val result = provider.performAction(account, "checkin", com.google.gson.JsonObject())
        assertTrue(result.ok, "签到应成功：${result.message}")
        assertTrue(result.message.contains("50"), "应报告获得积分，实际：${result.message}")
    }

    @Test
    fun `模型目录是静态表且解析别名`() {
        // 目录是静态表：只要有一个账号就直接给出，不需要打上游；
        // 无账号时按约定返回空目录（不误导用户点进去必然失败），所以这里必须传账号。
        assertTrue(provider.listModels(null).models.isEmpty(), "无账号不该列模型")
        val models = provider.listModels(importAccount())
        assertTrue(models.models.isNotEmpty())
        assertTrue(models.error.isEmpty(), "上游本来就没有模型列表接口，不该报错")
        assertEquals("model-text", provider.resolveModel(""))
        assertEquals("model-text", provider.resolveModel("dumate/model-text"))
        assertEquals("glm-5", provider.resolveModel("glm-5"))
        // 档位编码里的斜杠不能被当成通道前缀剥掉，否则模型名变成 text.L0，上游必拒
        assertEquals(
            "dm-auto-model/text.L1",
            provider.resolveModel("dm-auto-model/text.L1"),
            "dm-auto 系列的斜杠是模型名的一部分",
        )
        assertEquals(
            "dm-auto-model/text.L1",
            provider.resolveModel("dumate/dm-auto-model/text.L1"),
            "只剥一次 dumate/ 前缀，剩下的原样保留",
        )
    }

    @Test
    fun `cookie 规范化接受多种粘贴形态`() {
        assertEquals(
            "BDUSS=abc; STOKEN=def",
            DumateProvider.normalizeCookie("BDUSS=abc; STOKEN=def"),
        )
        assertEquals(
            "BDUSS=abc; STOKEN=def",
            DumateProvider.normalizeCookie("Cookie: BDUSS=abc;\nSTOKEN=def"),
        )
        assertEquals(
            "BDUSS=abc; STOKEN=def",
            DumateProvider.normalizeCookie("""{"cookie":"BDUSS=abc; STOKEN=def"}"""),
        )
        assertNull(DumateProvider.normalizeCookie("   "))
        assertNull(DumateProvider.normalizeCookie("没有等号的一串字"))
    }

    @Test
    fun `网络不通时报网络类错误而不是崩溃`() {
        upstream.stop()
        val account = DumateAccount(
            uid = "u1",
            cookie = "BDUSS=x",
            token = "",
            tokenExpiresAtMillis = 0,
        )
        val raw = ProviderAccount(DumateProvider.ID, "u1", "", account.toSecret())
        val call = provider.openChat(raw, """{"model":"model-text","stream":true,"messages":[]}""")
        assertEquals(401, call.status, "取不到 token 应报 401（需要重新登录）")
        assertTrue(call.errorBody.isNotEmpty())
    }
}
