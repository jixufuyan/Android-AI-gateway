package dev.aigw.core.provider.dumate

import dev.aigw.core.provider.ErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 错误分类的两个「别把好账号打死」的规则。
 *
 * 这两条都来自对照 dumate2api 的实现：
 * - 403 不等于凭证失效（云网关对模型未授权/参数越界也回 403），
 *   判成 SESSION_DEAD 会在 onAccountError 里直接禁用账号，一个参数错误就要重登。
 * - 400 属于客户端问题，宿主的 `GatewayHttpServer` 见 `ErrorKind.CLIENT` 会立刻返回、
 *   不换号——不然一个必然失败的请求会把池里所有账号都试一遍。
 */
class DumateErrorsTest {

    @Test
    fun `403 不被判成凭证失效`() {
        val (kind, _) = DumateErrors.classify(403, """{"error":{"message":"model not authorized"}}""")
        assertNotEquals(ErrorKind.SESSION_DEAD, kind)
        assertEquals(ErrorKind.CLIENT, kind)
    }

    @Test
    fun `401 仍是凭证失效`() {
        val (kind, _) = DumateErrors.classify(401, """{"error":{"message":"unauthorized"}}""")
        assertEquals(ErrorKind.SESSION_DEAD, kind)
    }

    @Test
    fun `400 归为客户端问题，宿主不会为它换号`() {
        val (kind, _) = DumateErrors.classify(
            400,
            """{"error":{"message":"parameter check failed, max_tokens range is [1, 393216]"}}""",
        )
        assertEquals(ErrorKind.CLIENT, kind)
    }

    @Test
    fun `HTTP 200 里的 10001 仍按凭证失效处理`() {
        val (kind, _) = DumateErrors.classify(200, """{"code":10001,"message":"login expired"}""")
        assertEquals(ErrorKind.SESSION_DEAD, kind)
    }

    @Test
    fun `积分耗尽与限流各自分类`() {
        assertEquals(ErrorKind.QUOTA, DumateErrors.classify(402, """{"message":"insufficient points"}""").first)
        assertEquals(ErrorKind.SOFT_RATE, DumateErrors.classify(429, """{"message":"too many requests"}""").first)
    }
}
