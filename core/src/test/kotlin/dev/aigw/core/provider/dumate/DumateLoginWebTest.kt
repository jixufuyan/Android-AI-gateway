package dev.aigw.core.provider.dumate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 登录页取值逻辑（真机上最容易出错的两点）。
 *
 * 1. 登录完可能落到 m.baidu.com，cookie 写在 `.baidu.com` 域下——只查搭子域读不到。
 * 2. 多个域返回的 cookie 会重复，必须合并去重，否则上游取到过期那条。
 */
class DumateLoginWebTest {

    @Test
    fun `只认 BDUSS 作为登录完成的判据`() {
        assertTrue(DumateLoginWeb.hasBduss("BDUSS=abc; STOKEN=def"))
        assertTrue(DumateLoginWeb.hasBduss("BAIDUID=x; BDUSS=abc"))
        // 有 BDUSS 这个键但没值（被截断）不算
        assertFalse(DumateLoginWeb.hasBduss("BDUSS=; STOKEN=def"))
        assertFalse(DumateLoginWeb.hasBduss("BAIDUID=abc"))
        assertFalse(DumateLoginWeb.hasBduss(""))
    }

    @Test
    fun `多域 cookie 合并去重且后者覆盖前者`() {
        val merged = DumateLoginWeb.mergeCookieSources(
            listOf(
                "BAIDUID=old; BDUSS=from-baidu",
                "BAIDUID=new; BDUSS=",
            ),
        )
        // 空值不覆盖已有值
        assertTrue(merged.contains("BDUSS=from-baidu"), merged)
        assertEquals("new", merged.substringAfter("BAIDUID=").substringBefore(";"))
    }

    @Test
    fun `搭子域返回空时也能从百度域拿到登录态`() {
        val merged = DumateLoginWeb.mergeCookieSources(
            listOf("", "", "", "BAIDUID=abc; BDUSS=xyz; STOKEN=st"),
        )
        assertTrue(DumateLoginWeb.hasBduss(merged))
        assertTrue(merged.contains("STOKEN=st"))
    }

    @Test
    fun `只有 BDUSS 不算登录完成`() {
        // 真机踩过：只拿到 BDUSS 就报成功，下一步换 token 必失败
        assertFalse(DumateLoginWeb.isLoginComplete("BDUSS=x; STOKEN=y"))
        // long-term 顶上不行（实测 401），必须凑齐四条
        assertFalse(DumateLoginWeb.isLoginComplete("BDUSS=x; bce-long-term-sessionid=s"))
        assertTrue(
            DumateLoginWeb.isLoginComplete(
                "BDUSS=x; bce-passport-stoken=t; bce-login-accountid=a; bce-sessionid=s",
            ),
        )
    }

    @Test
    fun `登录入口与控制台会话的约定`() {
        assertTrue(DumateLoginWeb.LOGIN_URL.startsWith("https://login.bce.baidu.com/"), "登录要从智能云入口进")
        // 必须断言 short 版：long-term 顶上实测照样 401
        assertEquals("bce-sessionid", DumateLoginWeb.CONSOLE_SESSION_COOKIE)
        assertTrue(
            DumateLoginWeb.COOKIE_SOURCES.first().contains("console.bce.baidu.com"),
            "控制台域要排在最前：会话与账号 ID 只在它下面建立",
        )
        assertTrue(DumateLoginWeb.SETTLE_URLS.isNotEmpty(), "缺会话时要有补救地址")
    }

    @Test
    fun `登录落点候选必须覆盖百度域`() {
        assertTrue(DumateLoginWeb.COOKIE_SOURCES.any { it.contains("dumate.cn") })
        assertTrue(
            DumateLoginWeb.COOKIE_SOURCES.any { it.contains("baidu.com") },
            "登录成功后可能落在 m.baidu.com，不在候选里就会读不到 BDUSS",
        )
    }

    @Test
    fun `补救地址是两步：控制台首页之后才是搭子应用页`() {
        // 控制台会话在首页就会种下，但搭子应用页要会话才不 401，顺序不能反
        assertEquals(2, DumateLoginWeb.SETTLE_URLS.size, "补救步数应当是 2")
        assertTrue(DumateLoginWeb.SETTLE_URLS[0].trimEnd('/') == "https://console.bce.baidu.com")
        assertTrue(DumateLoginWeb.SETTLE_URLS[1].contains("app/dumate"))
    }

    @Test
    fun `登录页地址指向控制台`() {
        assertTrue(DumateLoginWeb.LOGIN_URL.contains("redirect="))
        // redirect 是 URL 编码过的（console.bce.baidu.com 变成 %2F 分隔），断言编码形态
        assertTrue(
            DumateLoginWeb.LOGIN_URL.contains("console.bce.baidu.com") ||
                DumateLoginWeb.LOGIN_URL.contains("console.bce.baidu.com%2F") ||
                DumateLoginWeb.LOGIN_URL.contains("%2Fconsole.bce.baidu.com"),
            "登录页要跳回控制台：${DumateLoginWeb.LOGIN_URL}",
        )
    }
}
