package dev.aigw.core.provider.dumate

/**
 * 应用内网页登录页的取值逻辑（纯 JVM，可测）。
 *
 * 这里只放「登录成功没成功」「cookie 从哪里读」这两个判断，WebView 的创建与生命周期
 * 留在 app 层（[dev.aigw.app.ui.DumateLoginScreen]）。分开的理由很实在：这两个判断
 * 是真机上最容易出错的地方，而它们恰好不需要 Android 就能验证。
 *
 * ## 为什么要走「百度智能云控制台」而不是搭子自己的 wap 登录页
 *
 * 搭子的网页版并不是独立的账号体系，它就是百度智能云控制台里的一个应用
 * （`console.bce.baidu.com/ai/#/app/dumate/...`）。这一点从别的实现里实测得到、
 * 也从我们自己的真机反馈印证了：
 *
 * - 只在通行证登录（wappass）时，只会写下 `BDUSS`；拿它去打
 *   `/api/dumate/app/token/get` 会被拒（真机现象：`[已抓到：BDUSS]` 却换不到 token）。
 * - 真正让 `/api/dumate/…` 可用的是**控制台域的会话 cookie
 *   `bce-long-term-sessionid`**，它只在访问过 `console.bce.baidu.com` 之后才由服务端种下。
 * - 搭子的 wap 流程登录完会落到 `m.baidu.com` 首页，压根没进过控制台域。
 *
 * 所以登录必须从 `login.bce.baidu.com` 进、落在 `console.bce.baidu.com` 上，
 * 判据是「BDUSS + 控制台会话」两个都在，缺一个就主动导航一次控制台应用页把它种下来。
 */
object DumateLoginWeb {

    /**
     * 登录页入口：百度智能云的统一登录，`redirect` 指回控制台。
     *
     * 不要换成搭子自己的 `dumate.cn` 登录页——那条路只给 BDUSS，换不到 token。
     */
    const val LOGIN_URL =
        "https://login.bce.baidu.com/?redirect=https%3A%2F%2Fconsole.bce.baidu.com%2F"

    /**
     * 登录成功后的落点候选，按优先级排列（越靠前越优先，用于挑账号级 cookie 来源）。
     *
     * `console.bce.baidu.com` 必须在：`bce-long-term-sessionid` 只发给这个域。
     */
    val COOKIE_SOURCES = listOf(
        "https://console.bce.baidu.com/ai/",
        "https://www.dumate.cn/app",
        "https://dumate.cn",
        "https://m.baidu.com",
    )

    /**
     * 检测到 BDUSS 但还缺控制台会话时，主动访问这几页把会话 cookie 种下来。
     *
     * 顺序有意义：先访问控制台首页让会话落地，再进搭子应用页——搭子页本身要会话
     * 才不报 401，跳过首页直接进会白跑一次。
     */
    val SETTLE_URLS = listOf(
        // 控制台首页：负责把 bce-sessionid / bce-login-accountid 这套建起来
        "https://console.bce.baidu.com/",
        // 搭子应用页：进一次应用，服务端才会认「这个会话确实访问过搭子」
        "https://console.bce.baidu.com/ai/#/app/dumate/overview",
    )

    /**
     * 控制台会话 cookie 名。
     *
     * **必须是 `bce-sessionid`（短的那个）**，不是 `bce-long-term-sessionid`：
     * 实测只带 long-term 照样 401 `code=10001`，带 short 才 200。
     * 这条是拿真上游逐个字段对照出来的，注释里写反过一次，代价是登录一直失败。
     */
    const val CONSOLE_SESSION_COOKIE = "bce-sessionid"

    /**
     * 从一段 cookie 串里判断登录态是否**完整**。
     *
     * 两个条件都要：`BDUSS`（账号是谁）+ `bce-long-term-sessionid`（这个账号能调控制台 API）。
     * 只看 BDUSS 会得到「登录成功但换不到 token」——这正是真机上踩过的坑。
     */
    /**
     * 登录是否**完整**：四个必需字段齐了才算。
     *
     * 只看 BDUSS 会把「还差一步」误判成完成，然后拿残缺凭证去换 token、必失败。
     */
    fun isLoginComplete(cookie: String): Boolean =
        DumateCookiePick.missingKeys(cookie).isEmpty()

    fun hasBduss(cookie: String): Boolean = hasValue(cookie, "BDUSS")

    fun hasConsoleSession(cookie: String): Boolean = hasValue(cookie, CONSOLE_SESSION_COOKIE)

    private fun hasValue(cookie: String, name: String): Boolean =
        cookie.split(';').any {
            val trimmed = it.trim()
            trimmed.startsWith("$name=") && trimmed.length > name.length + 1
        }

    /**
     * 多来源合并成一条完整的 `k=v; k=v`。
     *
     * 同一 cookie 名在多个域下都有时以后出现的为准（域越具体越靠后），
     * 重复名会覆盖而不是堆两条——上游看到两条 `BDUSS` 会取第一条，可能刚好是过期的。
     */
    fun mergeCookieSources(sources: List<String>): String {
        val jar = LinkedHashMap<String, String>()
        for (raw in sources) {
            for (pair in raw.split(';')) {
                val trimmed = pair.trim()
                val index = trimmed.indexOf('=')
                if (index <= 0) continue
                val name = trimmed.substring(0, index).trim()
                val value = trimmed.substring(index + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) jar[name] = value
            }
        }
        return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
