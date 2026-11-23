package dev.aigw.core.provider.dumate

import dev.aigw.core.gateway.LoginState
import dev.aigw.core.provider.ProviderHooks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 网页登录收口的行为验证（打本地假上游，不碰真网络）。
 *
 * 覆盖「WebView 攒到的 cookie → 搭子账号」这条路径上真正会出错的判断：
 * cookie 不完整、cookie 换不到 token、cookie 在路径上是否原样带给了 token 接口。
 */
class DumateLoginTest {

    private fun loginFor(upstream: FakeDumateUpstream): DumateLogin {
        // 假上游的 web 端点和生产同构（token/get、user/info 都挂在 webBase 下），
        // 所以登录内核直接指向它，不需要额外打桩
        return DumateLogin(http = DumateLoginHttp(baseUrl = upstream.endpoints.webBase), endpoints = upstream.endpoints)
    }

    @Test
    fun `完整 cookie 能换出账号且 cookie 原样带到 token 接口`() {
        val upstream = FakeDumateUpstream().start()
        try {
            val login = loginFor(upstream)
            val outcome = login.accountFromCookie("BDUSS=abc; STOKEN=def")

            assertEquals(LoginState.SUCCESS, outcome.state, "应登录成功：${outcome.error}")
            assertEquals("u-123", outcome.uid)
            assertEquals("测试号", outcome.nickname)
            assertTrue(upstream.lastTokenCookie.contains("BDUSS=abc"), "token 接口要拿到整包 cookie")
            assertNotNull(login.lastAccount, "成功时要留下账号对象给引擎落池")
        } finally {
            upstream.stop()
        }
    }

    @Test
    fun `缺少 BDUSS 的 cookie 直接被拒且不落池`() {
        val upstream = FakeDumateUpstream().start()
        try {
            val login = loginFor(upstream)
            val outcome = login.accountFromCookie("BAIDUID=abc; STOKEN=def")

            assertEquals(LoginState.FAILED, outcome.state)
            assertTrue(outcome.error.contains("BDUSS"), "错误里要说明缺的是 BDUSS，实际：${outcome.error}")
            assertNull(login.lastAccount, "失败时不能留下账号对象")
        } finally {
            upstream.stop()
        }
    }

    @Test
    fun `token 接口拒绝时如实报失败而不是给个死账号`() {
        val upstream = FakeDumateUpstream().start()
        upstream.tokenStatus = 401
        upstream.tokenBody = """{"code":10001,"message":"登录态已失效"}"""
        try {
            val outcome = loginFor(upstream).accountFromCookie("BDUSS=abc")
            assertEquals(LoginState.FAILED, outcome.state)
            assertTrue(outcome.error.contains("token"), "错误应说明换 token 失败，实际：${outcome.error}")
        } finally {
            upstream.stop()
        }
    }

    @Test
    fun `空白 cookie 在解析层被拦下`() {
        val upstream = FakeDumateUpstream().start()
        try {
            val outcome = loginFor(upstream).accountFromCookie("   ")
            assertEquals(LoginState.FAILED, outcome.state)
            assertTrue(outcome.error.isNotEmpty())
        } finally {
            upstream.stop()
        }
    }

    @Test
    fun `Cookie 头与换行形态的 cookie 会被规范化`() {
        val upstream = FakeDumateUpstream().start()
        try {
            val raw = "Cookie: BDUSS=abc\nSTOKEN=def"
            val outcome = DumateLogin(
                http = DumateLoginHttp(baseUrl = upstream.endpoints.webBase),
                hooks = ProviderHooks(),
                endpoints = upstream.endpoints,
            ).accountFromCookie(raw)

            assertEquals(LoginState.SUCCESS, outcome.state, "规范化后应能登录：${outcome.error}")
            assertTrue(upstream.lastTokenCookie.contains("BDUSS=abc"))
        } finally {
            upstream.stop()
        }
    }
}
