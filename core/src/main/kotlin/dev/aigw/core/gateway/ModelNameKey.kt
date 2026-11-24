package dev.aigw.core.gateway

/**
 * 模型名归一化：让「同一款模型在不同供应商那里的不同写法」认成同一个。
 *
 * 各家的模型 id 是一锅粥，同一个 GLM-5.3-Flash 能写成五种样子：
 *
 * ```
 * 小浣熊     raccoon/sn-glm-5-3-flash     ← 带供应商前缀、版本号用短横
 * 智谱       autoglm/glm-5.3-flash        ← 版本号用点
 * 智谱清言   chatglm/glm-5.3-flash        ← 同上
 * WorkBuddy  codebuddy-cn/glm-5.3-flash   ← 前缀带区域后缀
 * Loomy      loomy/GLM-5.3-Flash          ← 全大写
 * ```
 *
 * 原来的故障转移用字符串全等比较，`sn-glm-5-3-flash` ≠ `glm-5.3-flash`，
 * 于是「同款模型」永远匹配不上，候选列表只剩自己，被限流了也没地方切。
 *
 * 这里给出 4 级匹配（数字越小越可信），只在「找不到更可信的」时才逐级放宽：
 *
 * | 级别 | 规则 | 说明 |
 * |---|---|---|
 * | 0 | 原文相等（忽略大小写） | 完全同名，绝不会错 |
 * | 1 | 规范化后相等 | 大小写、`.`/`_`/空格/连续短横、供应商前缀 |
 * | 2 | 去掉版本修饰词后相等 | `-latest`/`-preview`/`-high`/`-low`/`-thinking` 等 |
 * | 3 | 去掉纯数字尾巴后相等 | `-1017`、`-20250101`、`-001` 这类快照号 |
 *
 * ⚠️ 刻意**不**做「模糊包含」：`glm-5.3` 与 `glm-5.3-flash` 是两个不同档位的模型，
 * 混起来会把省钱请求打到贵模型上；`qwen-2.5-72b` 与 `qwen-2.5-7b` 更不能混。
 */
object ModelNameKey {

    /**
     * 供应商私有的模型名前缀：跨供应商认「同款模型」时先剥掉。
     *
     * 小浣熊的目录里模型全叫 `sn-xxx`；`raccoon-` 是早期版本与部分别名通道用的写法。
     */
    private val VENDOR_PREFIXES = listOf("sn-", "raccoon-", "sensenova-")

    /**
     * 版本修饰词：剥掉后是同档位模型。
     *
     * 只保留**明确不影响 SKU 档位**的几个：`-latest` 最新快照、`-preview` 预览版、
     * `-high`/`-low` 推理档位（沿用原 `isEquivalentModel` 的口径）。
     *
     * ⚠️ 刻意不含：
     * - `-mini`/`-max`/`-pro`/`-flash`/`-lite`/`-turbo`/`-air`：不同档位，价格差数倍；
     * - `-chat`：base 与 chat 调优版是两个模型；
     * - `-thinking`：思维链开关，能力与耗时都不同，不该悄悄换掉。
     */
    private val TIER_MODIFIERS = listOf("-latest", "-preview", "-high", "-low")

    /**
     * 纯数字快照号后缀的最小/最大位数。
     *
     * 下限取 3 是**关键**：`deepseek-v3` 与 `deepseek-v3.1` 规范化后是
     * `deepseek-v3` / `deepseek-v3-1`，若允许剥 1~2 位数字，两者会被误判成同一款
     * （而它们是不同的版本）。3 位以上基本只可能是日期/编号快照：
     * `-001`、`-1017`、`-20250101`。
     */
    private const val SNAPSHOT_MIN_DIGITS = 3
    private const val SNAPSHOT_MAX_DIGITS = 8

    /** 匹配级别：数值越小越可信。 */
    const val TIER_EXACT = 0
    const val TIER_CANONICAL = 1
    const val TIER_TIER = 2
    const val TIER_SNAPSHOT = 3

    /** 认不出：不该发生故障转移。 */
    const val TIER_NONE = Int.MAX_VALUE

    /**
     * 规范化：小写 → 剥 `models/` 与供应商前缀 → 各种分隔符统一成 `-` → 收掉连续与首尾短横。
     *
     * `sn-glm-5-3-flash` → `glm-5-3-flash`
     * `GLM-5.3-Flash`    → `glm-5-3-flash`
     * `glm_5.3 flash`    → `glm-5-3-flash`
     */
    fun canonical(raw: String): String {
        var name = raw.trim().lowercase().removePrefix("models/").trim()
        // 只剥一层：`sn-raccoon-x` 这种叠两个前缀的情形不存在，多剥反而会误伤
        for (prefix in VENDOR_PREFIXES) {
            if (name.startsWith(prefix) && name.length > prefix.length) {
                name = name.removePrefix(prefix)
                break
            }
        }
        val mapped = StringBuilder(name.length)
        for (ch in name) {
            when (ch) {
                '.', '_', ' ', '\u00A0', '·', '–', '—' -> mapped.append('-')
                else -> mapped.append(ch)
            }
        }
        // 收掉连续短横与首尾短横：`glm--5--3-` → `glm-5-3`
        val collapsed = StringBuilder(mapped.length)
        var lastDash = false
        for (ch in mapped) {
            if (ch == '-') {
                if (!lastDash) collapsed.append('-')
                lastDash = true
            } else {
                collapsed.append(ch)
                lastDash = false
            }
        }
        return collapsed.toString().trim('-')
    }

    /** 级别 2：再剥掉版本修饰词（`-latest`/`-preview`/`-high`…）。 */
    fun withoutTierModifier(canonicalKey: String): String {
        var key = canonicalKey
        var changed = true
        // 可能连着两个修饰词（`-preview-high`），循环剥干净
        while (changed) {
            changed = false
            for (modifier in TIER_MODIFIERS) {
                if (key.endsWith(modifier) && key.length > modifier.length) {
                    key = key.removeSuffix(modifier)
                    changed = true
                }
            }
        }
        return key
    }

    /** 级别 3：再剥掉纯数字快照号尾巴（`-1017` / `-20250101` / `-001`）。 */
    fun withoutSnapshot(canonicalKey: String): String {
        val idx = canonicalKey.lastIndexOf('-')
        if (idx <= 0 || idx == canonicalKey.length - 1) return canonicalKey
        val tail = canonicalKey.substring(idx + 1)
        if (tail.length !in SNAPSHOT_MIN_DIGITS..SNAPSHOT_MAX_DIGITS) return canonicalKey
        if (!tail.all { it.isDigit() }) return canonicalKey
        return canonicalKey.substring(0, idx)
    }

    /**
     * 比较两个模型名，返回匹配级别（[TIER_NONE] 表示认不出是同一款）。
     *
     * 逐级放宽，一旦命中就返回：这样「完全同名」永远不会被宽匹配规则影响。
     */
    fun matchTier(a: String, b: String): Int {
        if (a.isBlank() || b.isBlank()) return TIER_NONE
        if (a.trim().equals(b.trim(), ignoreCase = true)) return TIER_EXACT

        val keyA = canonical(a)
        val keyB = canonical(b)
        if (keyA.isEmpty() || keyB.isEmpty()) return TIER_NONE
        if (keyA == keyB) return TIER_CANONICAL

        val tierA = withoutTierModifier(keyA)
        val tierB = withoutTierModifier(keyB)
        if (tierA == tierB) return TIER_TIER

        // 快照号必须在「修饰词已剥」的基础上再剥，且只能剥纯数字
        if (withoutSnapshot(tierA) == withoutSnapshot(tierB)) return TIER_SNAPSHOT

        return TIER_NONE
    }

    /** 是否同一款模型（任一可接受级别命中）。 */
    fun isSameModel(a: String, b: String): Boolean = matchTier(a, b) != TIER_NONE
}
