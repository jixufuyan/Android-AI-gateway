package dev.aigw.core.provider.dumate

import com.google.gson.JsonParser
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 按模型的 `max_tokens` 钳制与自学习。
 *
 * 上游按模型转到不同后端（GLM / DeepSeek / Kimi / Qwen），各家范围不同。
 * 不钳制就会得到 400，而错误文案是上游原话，用户看不出是自己填大了。
 */
class DumateModelCapsTest {

    @BeforeTest
    fun reset() = DumateModelCaps.clearLearned()

    @Test
    fun `静态表里的模型按各自上界钳制`() {
        assertEquals(131_072L, DumateModelCaps.clamp("glm-5", 200_000))
        assertEquals(393_216L, DumateModelCaps.clamp("model-text", 500_000))
        assertEquals(393_216L, DumateModelCaps.clamp("dm-auto-model/text.L0", 999_999))
        // L1 是 kimi，实测 200 万仍被接受
        assertEquals(2_000_000L, DumateModelCaps.clamp("dm-auto-model/text.L1", 2_000_000))
    }

    @Test
    fun `没超限时一个字节都不改`() {
        assertEquals(1000L, DumateModelCaps.clamp("glm-5", 1000))
    }

    @Test
    fun `未知模型回落到默认上界`() {
        assertEquals(DumateModelCaps.DEFAULT_MAX_OUTPUT, DumateModelCaps.clamp("some-new-model", 9_999_999))
    }

    @Test
    fun `三种上游措辞都能学到上界`() {
        assertEquals(
            393_216L,
            DumateModelCaps.learn("glm-5", "parameter check failed, max_tokens range is [1, 393216]"),
        )
        assertEquals(
            131_000L,
            DumateModelCaps.learn("glm-5", "max_tokens参数非法：限制数值范围[1,131000]"),
        )
        assertEquals(
            65_536L,
            DumateModelCaps.learn("model-text", "Range of max_tokens should be [1, 65536]"),
        )
        // 学到之后马上生效
        assertEquals(65_536L, DumateModelCaps.clamp("model-text", 500_000))
    }

    @Test
    fun `认不出的错误体不改变任何上限`() {
        val before = DumateModelCaps.maxOutputOf("glm-5")
        assertEquals(null, DumateModelCaps.learn("glm-5", "something went wrong"))
        assertEquals(before, DumateModelCaps.maxOutputOf("glm-5"))
    }

    @Test
    fun `请求体里的 max_tokens 超限时被改写`() {
        val body = """{"model":"glm-5","max_tokens":200000,"messages":[{"role":"user","content":"hi"}]}"""
        val clamped = DumateProvider.clampMaxTokens(body, "glm-5")
        val obj = JsonParser.parseString(clamped).asJsonObject
        assertEquals(131_072L, obj.get("max_tokens").asLong)
        // 其它字段原样保留
        assertEquals("glm-5", obj.get("model").asString)
        assertEquals(1, obj.getAsJsonArray("messages").size())
    }

    @Test
    fun `没写 max_tokens 或没超限时原样返回`() {
        val noField = """{"model":"glm-5","messages":[]}"""
        assertEquals(noField, DumateProvider.clampMaxTokens(noField, "glm-5"))

        val small = """{"model":"glm-5","max_tokens":100}"""
        assertEquals(small, DumateProvider.clampMaxTokens(small, "glm-5"))
    }
}
