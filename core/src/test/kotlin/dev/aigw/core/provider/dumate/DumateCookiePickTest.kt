package dev.aigw.core.provider.dumate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * cookie 筛选：四个必需字段缺一不可。
 *
 * 这组用例来自真上游逐个字段对照（2026-11-15）：
 * `BDUSS` / `bce-passport-stoken` / `bce-login-accountid` / `bce-sessionid` 缺任一项
 * 都会 401 `code=10001`，其中最容易踩的是把 `bce-long-term-sessionid` 当成 `bce-sessionid`。
 */
class DumateCookiePickTest {

    private val full = "BDUSS=session-value; bce-passport-stoken=st; " +
        "bce-login-accountid=acc; bce-sessionid=sid; BAIDUID=baiduid; ppfuid=pp"

    @Test
    fun `四个必需字段齐了才算可用`() {
        assertTrue(DumateCookiePick.hasAccountCookie(DumateCookiePick.parse(full)))
    }

    @Test
    fun `只有 BDUSS 不算可用且要指出缺哪三项`() {
        val jar = DumateCookiePick.parse("BDUSS=x; BAIDUID=y")
        assertFalse(DumateCookiePick.hasAccountCookie(jar), "只有 BDUSS 时发出去必 401")
        assertEquals(
            listOf("bce-passport-stoken", "bce-login-accountid", "bce-sessionid"),
            DumateCookiePick.missingKeys(jar),
        )
    }

    @Test
    fun `long-term-sessionid 不能顶替 bce-sessionid`() {
        val jar = DumateCookiePick.parse(
            "BDUSS=x; bce-passport-stoken=st; bce-login-accountid=acc; bce-long-term-sessionid=lt",
        )
        assertFalse(DumateCookiePick.hasAccountCookie(jar), "实测 long-term 顶上照样 401")
        assertTrue(DumateCookiePick.missingKeys(jar).contains("bce-sessionid"))
    }

    @Test
    fun `pick 会挑必需字段最齐的那个来源`() {
        val sources = listOf(
            "BDUSS=x; BAIDUID=a",                                  // 控制台域只有半个登录态
            "BDUSS=x; bce-passport-stoken=st; bce-login-accountid=acc; bce-sessionid=sid",
        )
        val picked = DumateCookiePick.pick(sources)
        assertTrue(picked.contains("bce-sessionid=sid"), picked)
        assertTrue(picked.contains("BDUSS=x"), picked)
    }

    @Test
    fun `pick 不把不同来源的账号级字段混在一起`() {
        val sources = listOf(
            "BDUSS=main; bce-passport-stoken=st",
            "BDUSS=other; bce-sessionid=sid; bce-login-accountid=acc",
        )
        val picked = DumateCookiePick.pick(sources)
        // 两个来源都只有 3 项，取靠前的那个；绝不能出现 main 的 BDUSS 配 other 的 session
        val jar = DumateCookiePick.parse(picked)
        assertEquals(1, listOf("main", "other").count { it == jar["BDUSS"] })
    }

    @Test
    fun `没有任何来源有 BDUSS 时返回空串`() {
        assertEquals("", DumateCookiePick.pick(listOf("BAIDUID=a", "bce-sessionid=b")))
    }

    @Test
    fun `设备级字段可以跨来源互补`() {
        val picked = DumateCookiePick.pick(
            listOf(
                "BDUSS=x; bce-passport-stoken=st; bce-login-accountid=acc; bce-sessionid=sid",
                "BAIDUID=dev; ppfuid=uid",
            ),
        )
        assertTrue(picked.contains("BAIDUID=dev"), picked)
        assertTrue(picked.contains("ppfuid=uid"), picked)
    }

    @Test
    fun `诊断文案按来源说明齐全或缺项`() {
        val text = DumateCookiePick.describe(
            listOf("BDUSS=x; bce-sessionid=s; bce-passport-stoken=t; bce-login-accountid=a", "BDUSS=only"),
        )
        assertTrue(text.contains("齐全"), text)
        assertTrue(text.contains("console.bce.baidu.com"), text)
        assertTrue(text.contains("缺"), text)
    }

    @Test
    fun `登录完成判定与四条必需字段一致`() {
        assertTrue(DumateLoginWeb.isLoginComplete(full))
        assertFalse(DumateLoginWeb.isLoginComplete("BDUSS=x"))
    }
}