package dev.aigw.core.provider.dumate

/**
 * 登录成功后要用哪个 cookie 换 token。
 *
 * ## 实测结论（2026-11-15，对着真上游逐个字段做对照）
 *
 * `token/get` 需要**四个字段同时在**，少一个就 401 `code=10001`：
 *
 * ```
 * BDUSS                = 账号身份（完整长度 192；网页登录常抓到被截断的短版本）
 * bce-passport-stoken  = 通行证在控制台的票据
 * bce-login-accountid  = 控制台账号 ID
 * bce-sessionid        = 控制台会话 ← 缺它一定失败
 * ```
 *
 * 特别注意 **`bce-sessionid` 而不是 `bce-long-term-sessionid`**：
 * 后者单独带上照样 401。之前按注释里的说法判 `long-term` 是错的，
 * 表现就是「抓到了 BDUSS 却换不到 token」。
 *
 * ## 为什么还要分来源
 *
 * 内嵌 WebView 的 cookie 罐是跨域共享的，用户机器上早就有 `m.baidu.com` 的旧 BDUSS。
 * 混进去会让凭证与本次登录对不上，所以账号级字段**只取一个来源**；
 * 设备级（`BAIDUID` / `ppfuid`）可以互补。
 */
object DumateCookiePick {

    /** 换取 token 的必需字段（按重要性排列，诊断文案按这个顺序报缺项）。 */
    val REQUIRED_KEYS = listOf("BDUSS", "bce-passport-stoken", "bce-login-accountid", "bce-sessionid")

    /** 账号级 cookie：有就带上，不影响成败但能让上游更认可。 */
    val ACCOUNT_KEYS = listOf("STOKEN", "PTOKEN", "UBI", "PCSK", "BDUSS_BFESS")

    /** 设备级 cookie：从任何来源补都能提高风控通过率。 */
    val DEVICE_KEYS = listOf("BAIDUID", "BAIDUID_BFESS", "ppfuid", "PASSID")

    /** 每个来源探测时用的 URL；顺序即优先级（与 [DumateLoginWeb.COOKIE_SOURCES] 一致）。 */
    val SOURCES = DumateLoginWeb.COOKIE_SOURCES

    /** 解析成 `name -> value`，重复名字后者覆盖。 */
    fun parse(cookie: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in cookie.split(';')) {
            val trimmed = pair.trim()
            val index = trimmed.indexOf('=')
            if (index <= 0) continue
            val name = trimmed.substring(0, index).trim()
            val value = trimmed.substring(index + 1).trim()
            if (name.isNotEmpty() && value.isNotEmpty()) out[name] = value
        }
        return out
    }

    /**
     * 账号态是否**够换 token**：四个必需字段都在。
     *
     * 只有 BDUSS 时返回 false —— 那种情况下发出请求必 401，提前报「缺哪一项」比
     * 让用户对着「登录态不完整」猜要强得多。
     */
    fun hasAccountCookie(jar: Map<String, String>): Boolean =
        REQUIRED_KEYS.all { jar[it].orEmpty().isNotEmpty() }

    /** 缺哪些必需字段（按 [REQUIRED_KEYS] 顺序）。 */
    fun missingKeys(jar: Map<String, String>): List<String> =
        REQUIRED_KEYS.filter { jar[it].orEmpty().isEmpty() }

    fun missingKeys(cookie: String): List<String> = missingKeys(parse(cookie))

    /**
     * 从「按来源抓到的 cookie 列表」里拼出要发给搭子的那条。
     *
     * 选源规则：优先取**必需字段最齐**的来源；并列时取靠前的（控制台域在最前）。
     * 账号级字段只从该来源取，不从别的源借——借来的与本次登录对不上。
     * 返回空串表示没有任何可用凭证。
     */
    fun pick(sources: List<String>): String {
        val jars = sources.map { parse(it) }
        // 选「缺项最少」的来源；全都没 BDUSS 就放弃
        val chosen = jars
            .withIndex()
            .filter { it.value["BDUSS"].orEmpty().isNotEmpty() }
            .minByOrNull { missingKeys(it.value).size }
            ?.value
            ?: return ""
        if (chosen["BDUSS"].orEmpty().isEmpty()) return ""

        return buildMap {
            for (key in REQUIRED_KEYS + ACCOUNT_KEYS) {
                chosen[key]?.let { put(key, it) }
            }
            for (key in DEVICE_KEYS) {
                val value = chosen[key] ?: jars.firstNotNullOfOrNull { it[key] } ?: continue
                put(key, value)
            }
        }.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 给界面用的诊断文案：每个来源抓到几个字段、缺哪些必需项。 */
    fun describe(sources: List<String>): String {
        val parts = sources.mapIndexed { index, raw ->
            val jar = parse(raw)
            val label = SOURCES.getOrNull(index)?.substringAfter("://")?.substringBefore("/") ?: "source$index"
            val missing = missingKeys(jar)
            val state = when {
                jar.isEmpty() -> "空"
                missing.isEmpty() -> "齐全"
                else -> "缺 " + missing.joinToString("/")
            }
            "$label=${jar.size}项[$state]"
        }
        return parts.joinToString("；")
    }
}