package dev.aigw.core.provider.chatglm

import dev.aigw.core.provider.ProviderModel
import java.util.Locale

/**
 * 智谱清言模型目录。
 *
 * agent_id 不分模型——模型由请求里的 selected_model 决定，agent 由
 * project/list 解析（多个客户端模型名共用同一个默认智能体）。
 */
internal object ChatglmModels {

    /** selected_model 归一（对齐 1.5.9 参考实现 Ld/z 别名表）。 */
    private val ALIASES = mapOf(
        "glm-flash" to "glm-5.3-flash",
        "glm-5.3" to "glm-5.3",
    )

    /** 对外公告的模型。 */
    fun advertised(): List<ProviderModel> = listOf(
        ProviderModel(
            id = "glm-5.3-flash",
            name = "glm-5.3-flash（免费档）",
            contextWindow = 0,
            extra = mapOf("tier" to "智谱 GLM"),
        ),
        ProviderModel(
            id = "glm-5.3",
            name = "glm-5.3（旗舰）",
            contextWindow = 0,
            extra = mapOf("tier" to "智谱 GLM", "note" to "需要账号有对应权益"),
        ),
    )

    const val DEFAULT_MODEL = "glm-5.3-flash"

    /**
     * 归一 selected_model：① 剥 zaicoding_/zai_ 前缀；② 展示名映射回模型 ID；
     * ③ glm- 前缀的原样透传；其余回退默认 flash。
     */
    fun selectedModel(requested: String): String {
        var m = requested.trim()
        if (m.startsWith("zaicoding_")) m = m.removePrefix("zaicoding_")
        else if (m.startsWith("zai_")) m = m.removePrefix("zai_")
        val key = normalizeKey(m)
        ALIASES[key]?.let { return it }
        if (key.startsWith("glm-")) return m.lowercase(Locale.US)
        return DEFAULT_MODEL
    }

    /** 归一化比较键：去分隔符、小写（对齐 ModelUnionCache.normalizeKey 的效果）。 */
    fun normalizeKey(s: String): String =
        s.lowercase(Locale.US).filter { it.isLetterOrDigit() }
}
