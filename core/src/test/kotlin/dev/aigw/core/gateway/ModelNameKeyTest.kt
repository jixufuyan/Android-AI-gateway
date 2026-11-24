package dev.aigw.core.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 模型名归一化（[ModelNameKey]）：同一款模型在各家的不同写法必须能认成同一个。
 *
 * 这条链路的现实数据（全部来自用户实测）：
 *
 * ```
 * 小浣熊     raccoon/sn-glm-5-3-flash
 * 智谱       autoglm/glm-5.3-flash
 * 智谱清言   chatglm/glm-5.3-flash
 * WorkBuddy  codebuddy-cn/glm-5.3-flash
 * Loomy      loomy/GLM-5.3-Flash
 * ```
 */
class ModelNameKeyTest {

    @Test
    fun `五种写法的 glm-5-3-flash 全部认成同一款`() {
        val ids = listOf(
            "sn-glm-5-3-flash",
            "glm-5.3-flash",
            "GLM-5.3-Flash",
            "glm-5_3_flash",
            "glm 5.3 flash",
        )
        val key = ModelNameKey.canonical(ids.first())
        assertEquals("glm-5-3-flash", key)
        ids.forEach { id ->
            assertEquals(key, ModelNameKey.canonical(id), "规范化结果不一致：$id")
            // 与自身相比是同名字符串，级别是 EXACT；与其它写法只差写法，级别是 CANONICAL
            val tier = ModelNameKey.matchTier("sn-glm-5-3-flash", id)
            assertTrue(
                tier == ModelNameKey.TIER_EXACT || tier == ModelNameKey.TIER_CANONICAL,
                "$id 的匹配级别应为 EXACT 或 CANONICAL，实际 $tier",
            )
        }
    }

    @Test
    fun `大写与点号短横混写视为规范化同级`() {
        assertEquals(ModelNameKey.TIER_CANONICAL, ModelNameKey.matchTier("GLM-5.3-Flash", "glm-5-3-flash"))
        assertEquals(ModelNameKey.TIER_CANONICAL, ModelNameKey.matchTier("GLM-5.3-Flash", "sn-glm-5-3-flash"))
    }

    @Test
    fun `完全同名永远是最可信级别`() {
        assertEquals(ModelNameKey.TIER_EXACT, ModelNameKey.matchTier("glm-5.3-flash", "glm-5.3-flash"))
        assertEquals(ModelNameKey.TIER_EXACT, ModelNameKey.matchTier("GLM-5.3-Flash", "glm-5.3-flash"))
    }

    @Test
    fun `纯数字快照号可以剥掉`() {
        assertTrue(ModelNameKey.isSameModel("sn-deepseek-v4-1-flash-1017", "deepseek-v4-1-flash"))
        assertTrue(ModelNameKey.isSameModel("glm-5.3-flash-20250101", "glm-5.3-flash"))
        assertTrue(ModelNameKey.isSameModel("glm-5.3-flash-001", "glm-5.3-flash"))
    }

    @Test
    fun `一位两位的数字尾巴不算快照号`() {
        // deepseek-v3 与 deepseek-v3.1 是两个版本，不能因为「剥数字」被混成同一款
        assertFalse(ModelNameKey.isSameModel("deepseek-v3", "deepseek-v3.1"))
        assertFalse(ModelNameKey.isSameModel("glm-5", "glm-5.3"))
        assertFalse(ModelNameKey.isSameModel("glm-5-2", "glm-5"))
    }

    @Test
    fun `版本修饰词可以剥掉`() {
        assertTrue(ModelNameKey.isSameModel("gemini-3.8-flash-high", "gemini-3.8-flash"))
        assertTrue(ModelNameKey.isSameModel("glm-5.3-preview", "glm-5.3"))
        assertTrue(ModelNameKey.isSameModel("gemini-3.8-flash-latest", "gemini-3.8-flash"))
    }

    @Test
    fun `档位与能力词不剥`() {
        // thinking 是能力开关、chat 是对话调优版：悄悄换掉会改变行为
        assertFalse(ModelNameKey.isSameModel("claude-4-6-sonnet-thinking", "claude-4-6-sonnet"))
        assertFalse(ModelNameKey.isSameModel("glm-5.3-chat", "glm-5.3"))
    }

    @Test
    fun `不同档位绝不能混为一谈`() {
        // 这些名字只差一个词，但价格与能力差数倍——认错等于把请求打到贵的模型上
        val pairs = listOf(
            "glm-5.3-flash" to "glm-5.3",
            "glm-5.3-flash" to "glm-5.3-pro",
            "glm-5.3-air" to "glm-5.3-flash",
            "qwen-2.5-72b" to "qwen-2.5-7b",
            "gpt-4o" to "gpt-4o-mini",
            "deepseek-v4-pro" to "deepseek-v4-flash",
            "glm-5.3" to "glm-5.4",
        )
        pairs.forEach { (a, b) ->
            assertFalse(ModelNameKey.isSameModel(a, b), "$a 与 $b 不该认成同一款")
        }
    }

    @Test
    fun `空串与空白不参与匹配`() {
        assertEquals(ModelNameKey.TIER_NONE, ModelNameKey.matchTier("", "glm-5.3"))
        assertEquals(ModelNameKey.TIER_NONE, ModelNameKey.matchTier("   ", "glm-5.3"))
        assertFalse(ModelNameKey.isSameModel("", ""))
    }

    @Test
    fun `models 前缀与多余分隔符被规范化掉`() {
        assertEquals("glm-5-3", ModelNameKey.canonical("models/GLM-5.3"))
        assertEquals("glm-5-3", ModelNameKey.canonical("  sn--glm--5--3-  "))
    }

    @Test
    fun `候选目录里挑级别最高的那个`() {
        // 目录里同时有 flash 和 flash 的快照版：请求 flash 时应挑回原名，而不是快照版
        val candidates = listOf("glm-5.3-flash-1017", "glm-5.3-flash", "glm-5.3")
        val best = candidates.firstOrNull { ModelNameKey.matchTier(it, "glm-5.3-flash") == ModelNameKey.TIER_EXACT }
        assertEquals("glm-5.3-flash", best)
        // 反向：请求快照版时优先挑快照版自己
        val reverse = candidates.firstOrNull { ModelNameKey.matchTier(it, "glm-5.3-flash-1017") == ModelNameKey.TIER_EXACT }
        assertEquals("glm-5.3-flash-1017", reverse)
    }
}
