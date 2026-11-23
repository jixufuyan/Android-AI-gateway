package dev.aigw.core.provider.dumate

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.CreditUnit
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.str
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 搭子积分响应解析。
 *
 * 真上游（2026-11-15 实测）的积分字段**是带小数的字符串**：
 *
 * ```
 * result.totalPoints = "28700.00"
 * result.usedPoints  = "338.42"
 * result.subscription = [ {totalPoints:"25000.00", usedPoints:...}, ... ]
 * result.incremental  = [ {...}, {...}, {...} ]
 * ```
 *
 * 这一点决定了不能直接 `asLong`：Gson 对 `"28700.00"` 调 `asLong` 会抛
 * NumberFormatException，被 `runCatching` 吞成 0 —— 表现就是「额度显示 总 0 / 已用 0」，
 * 而账号其实是好的。所以这里统一按 BigDecimal 解析，再决定展示成整数还是两位小数。
 */
internal object DumateCredits {

    /** 一个积分包（订阅包或增量包）。 */
    data class Package(
        val kind: String,
        val total: BigDecimal,
        val used: BigDecimal,
        val expireAt: Long,
    ) {
        val left: BigDecimal get() = (total - used).max(BigDecimal.ZERO)
    }

    /** 解析结果；[known] 为 false 表示上游没给可识别字段（不是「没额度」）。 */
    data class Parsed(
        val total: BigDecimal,
        val used: BigDecimal,
        val subscribed: Boolean,
        val packages: List<Package>,
        val known: Boolean,
    ) {
        val left: BigDecimal get() = (total - used).max(BigDecimal.ZERO)
    }

    /**
     * 解析额度响应体。拿不到可识别字段时返回 `known = false`，让界面显示「—」而不是 0。
     */
    fun parse(body: String): Parsed {
        val root = runCatching { com.google.gson.JsonParser.parseString(body).asJsonObject }.getOrNull()
        val payload = root?.let { unwrap(it) } ?: JsonObject()

        val total = number(payload, "totalPoints", "total_points", "total", "totalQuota", "quota")
        val used = number(payload, "usedPoints", "used_points", "used", "usedQuota")
        val remain = number(payload, "remainPoints", "remain_points", "remainingPoints", "leftPoints")
        // 有 remain 就以它为准：上游 total-used 与 remain 可能不同步（包汇总有延迟），
        // 剩余值最接近「现在还能用多少」。用「total = remain + used」反推，保证 left 恒等于 remain
        val effTotal = if (remain > BigDecimal.ZERO) remain + used else total
        val subscribed = payload.get("isSubscribed")
            ?.let { runCatching { it.asBoolean }.getOrNull() } ?: false

        val packages = buildList {
            addAll(packages(payload.arrayOrNull("subscription"), "订阅包"))
            addAll(packages(payload.arrayOrNull("incremental"), "增量包"))
        }
        val known = total > BigDecimal.ZERO || used > BigDecimal.ZERO || remain > BigDecimal.ZERO
        return Parsed(effTotal, used, subscribed, packages, known)
    }

    private fun packages(arr: JsonArray?, kind: String): List<Package> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val total = number(obj, "totalPoints", "total")
            val used = number(obj, "usedPoints", "used")
            if (total == BigDecimal.ZERO && used == BigDecimal.ZERO) return@mapNotNull null
            Package(
                kind = kind,
                total = total,
                used = used,
                // expireDate 是秒级时间戳
                expireAt = obj.get("expireDate")?.let { runCatching { it.asLong }.getOrNull() } ?: 0L,
            )
        }
    }

    /** 取数字字段：兼容数字字面量与字符串（含 `"28700.00"` 这种小数）。 */
    fun number(obj: JsonObject, vararg keys: String): BigDecimal {
        for (key in keys) {
            val v = obj.get(key) ?: continue
            if (v.isJsonNull || !v.isJsonPrimitive) continue
            val text = runCatching { v.asString }.getOrNull()?.trim().orEmpty()
            if (text.isEmpty()) continue
            val value = runCatching { BigDecimal(text) }.getOrNull() ?: continue
            if (value > BigDecimal.ZERO || value == BigDecimal.ZERO) return value
        }
        return BigDecimal.ZERO
    }

    /** 展示用：整数就不带小数点，有小数就保留两位（积分上游给的就是两位）。 */
    fun format(value: BigDecimal): String {
        val stripped = value.setScale(2, RoundingMode.DOWN).stripTrailingZeros()
        return if (stripped.scale() <= 0) stripped.toBigInteger().toString() else stripped.toPlainString()
    }

    /** 转成 [CreditInfo]：余额字段是 Long，小数部分向下取整（不夸大可用额度）。 */
    fun toCreditInfo(parsed: Parsed, rawBody: String): CreditInfo {
        if (!parsed.known) {
            return CreditInfo(
                balance = 0,
                known = false,
                detail = "额度接口没有返回可识别的积分字段：" + rawBody.take(200),
                unit = CreditUnit.CREDITS,
            )
        }
        val detail = buildString {
            append("总 ${format(parsed.total)} / 已用 ${format(parsed.used)}")
            if (parsed.subscribed) append(" · 已订阅")
            if (parsed.packages.isNotEmpty()) {
                append("（")
                append(parsed.packages.joinToString("，") { p ->
                    "${p.kind} ${format(p.total)}/${format(p.used)}"
                })
                append("）")
            }
        }
        return CreditInfo(
            balance = parsed.left.toLong(),
            known = true,
            detail = detail,
            unit = CreditUnit.CREDITS,
        )
    }

    /** 与 [DumateProvider.unwrap] 同一套信封规则：`result` 优先，其次 `data`。 */
    private fun unwrap(obj: JsonObject): JsonObject =
        obj.objOrNull("result") ?: obj.objOrNull("data") ?: obj
}