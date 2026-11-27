package dev.aigw.core.gateway

import com.google.gson.JsonParser
import dev.aigw.core.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「注入提示词（prepend）」与「追加提示词（append）」的行为差异。
 *
 * 这两个设置在界面上长得像，实际语义是相反的两件事，混用会让人以为「都一样」：
 *
 * - **prepend** 拼在第一条 system 的**前面**：`注入文本 + "\n\n" + 原 system`。
 *   用于破甲 / 角色设定这类必须先于客户端自身指令生效的内容。
 * - **append** 拼在第一条 system 的**后面**：`原 system + 追加文本`。
 *   用于补充约束，不抢客户端原指令的位置。
 *
 * 两者**只有在没有任何 system 消息时结果才相同**（都会新建一条 system），
 * 这也正是「看起来一样」的来源。这里把这个边界钉死。
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

    private val originalSystem = """{"model":"m","messages":[{"role":"system","content":"客户端原指令"},{"role":"user","content":"你好"}]}"""

    @Test
    fun `prepend 拼在原 system 前面`() {
        val out = apply(
            MiddlewareSettings(systemPromptPrepend = "【注入】", systemPromptPrependEnabled = true),
            originalSystem,
        )
        val content = firstSystemContent(out)
        assertTrue(content.startsWith("【注入】"), "prepend 必须排在最前：$content")
        assertTrue(content.endsWith("客户端原指令"), "原 system 必须完整保留在后面：$content")
    }

    @Test
    fun `append 拼在原 system 后面`() {
        val out = apply(
            MiddlewareSettings(systemPromptAppend = "【追加】", systemPromptAppendEnabled = true),
            originalSystem,
        )
        val content = firstSystemContent(out)
        assertTrue(content.startsWith("客户端原指令"), "append 不能抢到最前：$content")
        assertTrue(content.endsWith("【追加】"), "append 必须拼在末尾：$content")
    }

    @Test
    fun `两者同时开启时顺序是 注入 原指令 追加`() {
        val out = apply(
            MiddlewareSettings(
                systemPromptPrepend = "【注入】", systemPromptPrependEnabled = true,
                systemPromptAppend = "【追加】", systemPromptAppendEnabled = true,
            ),
            originalSystem,
        )
        val content = firstSystemContent(out)
        assertEquals("【注入】\n\n客户端原指令【追加】", content)

        // 消息条数不变（没有额外新增 system）
        assertEquals(
            2,
            JsonParser.parseString(out).asJsonObject.getAsJsonArray("messages").size(),
        )
    }

    @Test
    fun `没有 system 消息时两者结果相同（都新建一条 system）`() {
        val noSystem = """{"model":"m","messages":[{"role":"user","content":"你好"}]}"""

        val prepended = apply(
            MiddlewareSettings(systemPromptPrepend = "X", systemPromptPrependEnabled = true),
            noSystem,
        )
        val appended = apply(
            MiddlewareSettings(systemPromptAppend = "X", systemPromptAppendEnabled = true),
            noSystem,
        )

        assertEquals(firstSystemContent(prepended), "X")
        assertEquals(firstSystemContent(appended), "X")
        assertEquals(
            JsonParser.parseString(prepended).asJsonObject.getAsJsonArray("messages").size(),
            JsonParser.parseString(appended).asJsonObject.getAsJsonArray("messages").size(),
        )
    }

    @Test
    fun `开关关闭时不产生任何改写`() {
        val out = apply(
            MiddlewareSettings(
                systemPromptPrepend = "【注入】", systemPromptPrependEnabled = false,
                systemPromptAppend = "【追加】", systemPromptAppendEnabled = false,
            ),
            originalSystem,
        )
        val content = firstSystemContent(out)
        assertEquals("客户端原指令", content, "开关关闭时内容必须原样：$content")
        assertFalse(content.contains("【注入】"))
        assertFalse(content.contains("【追加】"))
    }

    @Test
    fun `默认设置下两个开关都是关闭的`() {
        val defaults = MiddlewareSettings()
        assertFalse(defaults.systemPromptPrependEnabled, "注入开关默认必须关闭")
        assertFalse(defaults.systemPromptAppendEnabled, "追加开关默认必须关闭")
    }

    @Test
    fun `老配置没有开关字段时按有内容即视为开启`() {
        // 升级前的存档没有 systemPromptAppendEnabled / systemPromptPrependEnabled 两个键
        val legacy = """{"aliases":{},"systemPromptPrepend":"旧注入","systemPromptAppend":"旧追加","stripThinkTags":false}"""
        val parsed = MiddlewareSettings.fromJson(legacy)
        assertTrue(parsed.systemPromptPrependEnabled, "老配置有注入内容时不应被静默关掉")
        assertTrue(parsed.systemPromptAppendEnabled, "老配置有追加内容时不应被静默关掉")
        assertEquals("旧注入", parsed.systemPromptPrepend)
        assertEquals("旧追加", parsed.systemPromptAppend)
    }
}