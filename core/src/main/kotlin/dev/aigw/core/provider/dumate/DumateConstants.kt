package dev.aigw.core.provider.dumate

/**
 * 百度搭子（DuMate）网页链路的端点与常量。
 *
 * 这条链路**不需要官方桌面客户端**：用浏览器登录 www.dumate.cn 得到的 cookie
 * 换一枚 1 小时有效期的 bearer token，再拿它直连云端网关的 OpenAI 兼容端点。
 * 桌面客户端那条路（本地 :8980 的 Go 后端 + `%APPDATA%\qianfan-desktop-app\auth.json`）
 * 依赖 Windows 路径，Android 上无法复现，因此这里只实现网页链路。
 */
internal object DumateConstants {

    /** 网页站点：cookie 换 token、积分/签到等业务接口都挂在它下面。 */
    const val WEB_BASE = "https://www.dumate.cn"

    /** 云端模型网关：OpenAI 兼容的 `/gateway/apis/v1`。 */
    const val GATEWAY_HOST = "dumate-svc.baidu.com"
    const val GATEWAY_PREFIX = "/gateway/apis/v1"

    /** 对话端点（拼在 [GATEWAY_PREFIX] 之后）。 */
    const val PATH_CHAT_COMPLETIONS = "/chat/completions"

    /** cookie → token。 */
    const val PATH_TOKEN = "/api/dumate/app/token/get"

    /** 账号信息（验证 cookie 是否有效、取昵称与 uid）。 */
    const val PATH_USER_INFO = "/api/dumate/user/info"

    /** 积分总览。 */
    const val PATH_QUOTA_OVERVIEW =
        "/api/dumate/points/quota_overview?clientType=desktop&timezone=Asia%2FShanghai"

    /** 每日签到。 */
    const val PATH_LOGIN_BONUS = "/api/dumate/points/loginBonus"

    /** 签到状态。 */
    /**
     * 签到状态查询（实测 2026-11-15 可用，200）。
     *
     * 返回 `result.{totalTimes, totalPoints, hasIssued, signInDays[]}`——
     * 「今天签没签」看 `hasIssued`，签到发多少分看 `totalPoints`。
     * 注意 `loginBonus/info`、`loginbonusinfo`、`signin/info` 这几个拼法都是 404。
     */
    const val PATH_LOGIN_BONUS_INFO = "/api/dumate/points/loginBonusInfo"

    /** 网页端请求必须带这个头，缺了会被当成非浏览器来源拒绝。 */
    const val CLIENT_TYPE_HEADER = "X-Dumate-Client-Type"
    const val CLIENT_TYPE_WEB = "web"

    /** 账号域标记：网页链路固定 `global`。 */
    const val ACCOUNT_ID_HEADER = "X-Dumate-Account-Id"
    const val ACCOUNT_ID_GLOBAL = "global"

    /** 云端网关回传的真实后端模型（用于把别名还原成实际模型名）。 */
    const val REAL_MODEL_HEADER = "X-Dumate-Gw-Realmodel"

    /** token 提前刷新的余量：留 5 分钟，避免请求刚好压在过期点上。 */
    const val REFRESH_SKEW_MS = 5 * 60 * 1000L

    /** 上游未给 expired_at 时的兜底有效期（宁可早刷不要晚刷）。 */
    const val FALLBACK_TOKEN_TTL_MS = 50 * 60 * 1000L

    const val CONNECT_TIMEOUT_MS = 20_000
    const val TOKEN_TIMEOUT_MS = 15_000

    /** 对话读超时：长任务可以跑很久，与参考实现一致给 10 分钟。 */
    const val CHAT_READ_TIMEOUT_MS = 600_000

    /** 网页业务接口读超时。 */
    const val WEB_READ_TIMEOUT_MS = 30_000

    /** 浏览器 UA：上游会按 UA 判断来源，用桌面 Chrome 的真实串。 */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    /** 上游用这两个业务码表示登录态失效。 */
    const val CODE_SESSION_DEAD = 10001L
    const val CODE_SESSION_DEAD_ALT = 10006L
}
