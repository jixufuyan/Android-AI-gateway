package dev.aigw.core.provider

/**
 * 同款模型自动合并（移植自本地网关的 ModelUnionCache 归一化规则）。
 *
 * <p>为什么要合并：同一家上游在不同供应商处会以不同写法出现同一个模型——
 * `glm-5.3-flash` / `GLM-5.3-Flash` / `glm-5-3-flash-20250101`，
 * 模型列表就会冒出好几条同名不同写法的条目，用户勾选「精选名单」时也会重复勾。
 * 归一到同一个 key 后只保留一条代表条目，路由层本来就会「同款模型跨供应商 Failover」，
 * 所以合并展示不会降低可用性。
 * 小浣熊的 `sn-` 前缀不在归一范围（那是供应商身份，见 [IGNORABLE_PREFIXES]）。</p>
 *
 * <p>铁律：<b>只合并归一化后完全相同的名字</b>。不做「同系列归并」（比如把 `gpt-5`
 * 和 `gpt-5-mini` 并成一条），那是泼脏水——两者能力不同、价格不同。</p>
 */
object ModelMerge {

    /** 去掉这些尾巴再比较：同一个模型的「发行版标记」，不影响能力。 */
    private val SUFFIXES = listOf(
        "-official", "-latest", "-preview", "-exp", "-beta", "-free", ":free",
    )

    /** 分隔符统一去掉：`glm-5.3` / `glm_5_3` / `glm 5.3` 归一为同一个 key。 */
    private val SEPARATORS = Regex("[-_.:\\s]+")

    /** 日期/版本尾巴：`-20250101`、`-20260811` 这类 6~8 位数字后缀。 */
    private val DATE_SUFFIX = Regex("-\\d{6,8}$")

    /** 只影响展示名、不影响模型身份的常见前缀（归一后让写法的两个变体相遇）。 */
    private val IGNORABLE_PREFIXES = listOf("doubao", "sn", "zai", "zaicoding", "custommodel")

    /**
     * 归一化 key：小写、去 provider 前缀、去日期尾、去发行版尾、去分隔符、去无意义前缀。
     *
     * 空串意味着无法归一（例如名字里只有分隔符），调用方应原样保留、不参与合并。
     */
    fun normalizeKey(rawId: String): String {
        var s = rawId.trim().lowercase()
        if (s.isEmpty()) return ""
        // `trae/glm-5.3-flash` 这类带供应商前缀的写法，只取最后一段
        val slash = s.lastIndexOf('/')
        if (slash >= 0 && slash < s.length - 1) s = s.substring(slash + 1)
        s = DATE_SUFFIX.replace(s, "")
        for (suffix in SUFFIXES) {
            if (s.endsWith(suffix) && s.length > suffix.length) {
                s = s.removeSuffix(suffix)
            }
        }
        s = SEPARATORS.replace(s, "")
        for (prefix in IGNORABLE_PREFIXES) {
            if (s.startsWith(prefix) && s.length > prefix.length) {
                s = s.removePrefix(prefix)
                break
            }
        }
        return s
    }

    /**
     * 合并同款模型，保留首次出现的顺序。
     *
     * 代表条目取「名字更规整」的那个：长度短的优先（`glm-5.3-flash` 胜过
     * `glm-5.3-flash-20250101`），长度相同则按字典序，保证同名两次调用结果稳定。
     */
    fun mergeSameModel(models: List<RoutedModel>): List<RoutedModel> {
        if (models.size <= 1) return models
        val indexByKey = LinkedHashMap<String, Int>()
        val out = ArrayList<RoutedModel>(models.size)
        for (model in models) {
            // 只合并同一供应商内部的同物异名：跨供应商合并会让 A 家的条目被
            // B 家更短/字典序更小的名字吞掉，开放名单里勾了却看不见（真实踩过：
            // chatglm/glm-5-3-flash 被并进 autoglm/glm-5-3-flash）。
            // 跨供应商的同款模型路由层本来就会 Failover，分开展示不损可用性。
            val key = model.providerId + "/" + normalizeKey(model.model.id)
            if (key.isEmpty()) {
                out.add(model)
                continue
            }
            val existing = indexByKey[key]
            if (existing == null) {
                indexByKey[key] = out.size
                out.add(model)
                continue
            }
            val prev = out[existing]
            if (isBetterRepresentative(model.model.id, prev.model.id)) {
                out[existing] = model
            }
        }
        return out
    }

    private fun isBetterRepresentative(candidate: String, current: String): Boolean =
        if (candidate.length != current.length) {
            candidate.length < current.length
        } else {
            candidate < current
        }
}
