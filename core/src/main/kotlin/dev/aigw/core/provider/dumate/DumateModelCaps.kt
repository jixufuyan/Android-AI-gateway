package dev.aigw.core.provider.dumate

/**
 * 按模型的 `max_tokens` 上限。
 *
 * 搭子的云网关是**按模型把请求转发到不同上游**的（千帆 GLM / 千帆 DeepSeek / Kimi /
 * 阿里百炼 Qwen），各家的 `max_tokens` 取值范围不同，且 400 错误体会原样透传上游声明。
 * 客户端传 `max_tokens=200000` 打 glm-5 会直接 400，而错误文案是上游原话
 * （「参数非法」之类），用户根本看不出是自己填大了。
 *
 * 两条来源：
 * 1. **静态表**：实测或上游 400 声明里得到的确定值（`dumate2api` 的 `dumate-caps.js`
 *    与我们的 `DumateModelCatalog` 同源）。
 * 2. **自学习**：撞到 400 且错误体带范围声明时，把 N 记下来，后续 clamp 用学习值——
 *    上游放宽或收紧都能在一次 400 之后自动跟上，不需要发新版。
 */
object DumateModelCaps {

    /** 没有条目的模型回落到这个全局上限（上游通用范围上界）。 */
    const val DEFAULT_MAX_OUTPUT = 131_072L

    /** 静态上限表：键是**发给上游的模型名**（已剥掉 `dumate/` 前缀）。 */
    private val STATIC = mapOf(
        "model-text" to 393_216L,
        "dm-auto-model/text.L0" to 393_216L,
        "dm-auto-model/mm.L0" to 393_216L,
        "dm-auto-model/mm.L2" to 393_216L,
        // 实测 2,000,000 仍被接受（未探顶），按已知下界给
        "dm-auto-model/text.L1" to 2_000_000L,
        "dm-auto-model/text.L2" to 131_072L,
        "glm-5" to 131_072L,
    )

    /** 运行期学习到的上限（进程内存即可：它只是「少挨一次 400」的优化）。 */
    private val learned = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 上游三种范围声明（各家措辞不同，2026-10 实测）：
     * - 千帆：`parameter check failed, max_tokens range is [1, 393216]`
     * - GLM（code 1210）：`max_tokens参数非法：限制数值范围[1,131072]`
     * - 阿里云：`Range of max_tokens should be [1, 131072]`
     */
    private val RANGE_PATTERNS = listOf(
        Regex("""max_tokens range is \[\s*1\s*,\s*(\d+)\s*]""", RegexOption.IGNORE_CASE),
        Regex("""限制数值范围\s*\[\s*1\s*,\s*(\d+)\s*]"""),
        Regex("""Range of max_tokens should be \[\s*1\s*,\s*(\d+)\s*]""", RegexOption.IGNORE_CASE),
    )

    /** 某模型允许的最大输出 token 数。 */
    fun maxOutputOf(model: String): Long =
        learned[model] ?: STATIC[model] ?: DEFAULT_MAX_OUTPUT

    /** 把超限的 `max_tokens` 拉回上界；没超或没写就原样返回（不改请求体）。 */
    fun clamp(model: String, requested: Long): Long {
        val cap = maxOutputOf(model)
        return if (requested > cap) cap else requested
    }

    /**
     * 从 400 响应体里学习上限。返回学到的值（没学到返回 null）。
     *
     * 只认「上界数字」，不猜别的字段：认不出就丢弃，绝不影响主流程。
     * 学到的值比静态表小时也记——那说明上游收紧了，跟着它走才不会继续 400。
     */
    fun learn(model: String, errorBody: String): Long? {
        if (model.isEmpty() || errorBody.isEmpty()) return null
        for (pattern in RANGE_PATTERNS) {
            val value = pattern.find(errorBody)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: continue
            if (value <= 0) continue
            learned[model] = value
            return value
        }
        return null
    }

    /** 学习结果快照（诊断/测试用）。 */
    fun snapshot(): Map<String, Long> = learned.toMap()

    /** 测试用：清空学习值。 */
    fun clearLearned() = learned.clear()
}
