package dev.aigw.core.gateway

import com.google.gson.JsonParser
import dev.aigw.core.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「system 提示词追加」的语义，以及前置注入已被移除的锁定。
 *
 * 前置注入（prepend）曾经存在，把文本拼在第一条 system 的**最前面**。
 * 但实测（deepseek-v4.1-flash / kimi-k3 / gpt-5.4 三个模型）它全部**静默失效**：
 * 拼在前面的指令会被后面的客户端 system 压住，效果比不开还弱，而且从界面完全
 * 看不出没生效。追加（append）拼在末尾、紧贴 user 消息，靠近因效应才是真正生效的那个。
 *
 * 所以前置注入被整体移除，这里锁住：
 * 1. 追加拼在原 system **末尾**，不抢位置；
 * 2. 前置注入字段即使有内容也不再产生任何改写（字段仅为保留老数据而存在）；
 * 3. 默认关闭；老配置不会因为升级而静默启用。
 */
class PromptPrependAppendDiffTest {

    private fun apply(settings: MiddlewareSettings, body: String): String {
        val chain = MiddlewareChain.build(settings)
        return chain.apply(
            MiddlewareContext(requestedModel = "m", providerId = "", upstreamModel = ""),
            JsonParser.parseString(body).asJsonObject,
        ).toString()
    }

    private fun firstSystemContent(json: String): String =
        JsonParser.parseString(json).asJsonObject
            .getAsJsonArray("messages")[0].asJsonObject
            .get("content").asString

    private val originalSystem =
        """{"model":"m","messages":[{"role":"system","content":"客户端原指令"},{"role":"user","content":"你好"}]}"""

    @Test
    fun `追加拼在原 system 末尾`() {
        val out = apply(
            MiddlewareSettings(systemPromptAppend = "【追加】", systemPromptAppendEnabled = true),
            originalSystem,
        )
        val content = firstSystemContent(out)
        assertEquals("客户端原指令【追加】", content)
    }

    @Test
    fun `前置注入字段有内容也不再产生任何改写`() {
        // 模拟升级前留下的老数据：prepend 里还有一长段文本。
        // 该字段已不参与链路，必须完全不出现。
        val out = apply(
            MiddlewareSettings(systemPromptPrepend = "【已废弃的注入】"),
            originalSystem,
        )
        val content = firstSystemContent(out)
        assertEquals("客户端原指令", content, "前置注入已移除，不应改写 system：$content")
        assertFalse(content.contains("【已废弃的注入】"))
    }

    @Test
    fun `只有追加生效时消息条数不变`() {
        val out = apply(
            MiddlewareSettings(systemPromptAppend = "X", systemPromptAppendEnabled = true),
            originalSystem,
        )
        assertEquals(
            2,
            JsonParser.parseString(out).asJsonObject.getAsJsonArray("messages").size(),
        )
    }

    @Test
    fun `没有 system 消息时追加会新建一条并放在最前`() {
        val noSystem = """{"model":"m","messages":[{"role":"user","content":"你好"}]}"""
        val out = apply(
            MiddlewareSettings(systemPromptAppend = "X", systemPromptAppendEnabled = true),
            noSystem,
        )
        val messages = JsonParser.parseString(out).asJsonObject.getAsJsonArray("messages")
        assertEquals(2, messages.size())
        assertEquals("system", messages[0].asJsonObject.get("role").asString)
        assertEquals("X", firstSystemContent(out).trim())
        assertEquals("user", messages[1].asJsonObject.get("role").asString)
    }

    @Test
    fun `开关关闭时不产生任何改写`() {
        val out = apply(
            MiddlewareSettings(systemPromptAppend = "【追加】", systemPromptAppendEnabled = false),
            originalSystem,
        )
        val content = firstSystemContent(out)
        assertEquals("客户端原指令", content, "开关关闭时内容必须原样：$content")
        assertFalse(content.contains("【追加】"))
    }

    @Test
    fun `默认设置下追加开关是关闭的`() {
        val defaults = MiddlewareSettings()
        assertFalse(defaults.systemPromptAppendEnabled, "追加开关默认必须关闭")
        assertTrue(defaults.systemPromptAppend.isBlank())
    }

    @Test
    fun `老配置没有开关字段时按有内容即视为开启`() {
        // 升级前的存档没有 systemPromptAppendEnabled 这个键
        val legacy =
            """{"aliases":{},"systemPromptPrepend":"旧注入","systemPromptAppend":"旧追加","stripThinkTags":false}"""
        val parsed = MiddlewareSettings.fromJson(legacy)
        assertTrue(parsed.systemPromptAppendEnabled, "老配置有追加内容时不应被静默关掉")
        assertEquals("旧追加", parsed.systemPromptAppend)
        // 前置注入的老文本仍被读回（不丢数据），但不参与任何改写
        assertEquals("旧注入", parsed.systemPromptPrepend)
    }

    @Test
    fun `isActive 不再被前置注入字段点亮`() {
        val onlyPrepend = MiddlewareSettings(systemPromptPrepend = "有内容")
        assertFalse(onlyPrepend.isActive, "只填了废弃字段时不应显示为已启用")

        val withAppend = MiddlewareSettings(systemPromptAppend = "有内容", systemPromptAppendEnabled = true)
        assertTrue(withAppend.isActive)
    }
}