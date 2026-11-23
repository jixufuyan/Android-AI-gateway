package dev.aigw.core.provider.autoglm

/**
 * 智谱 AutoGLM（autoglm-acceleration-api.zhipuai.cn）端点与常量。
 *
 * 协议事实（移植自本地网关 AutoGLMClient.java，对上游实测，不是推断）：
 * - 登录：POST /userapi/v1/agent-send-code → /userapi/v1/agent-login，
 *   两者必须复用同一 device_id（64 位 hex）。
 * - 对话：POST /autoclaw-proxy/proxy/autoclaw/chat/completions，OpenAI 兼容，
 *   认证头是 X-Authorization（**不是** Authorization）。
 * - 签名 API（模型/钱包/刷新/签到）：X-Auth-Sign = md5("100003&" + ts + "&" + 盐)。
 */
internal object AutoglmConstants {
    const val BASE = "https://autoglm-acceleration-api.zhipuai.cn"

    const val PATH_CHAT = "/autoclaw-proxy/proxy/autoclaw/chat/completions"
    const val PATH_MODEL_CONFIG = "/autoclaw-proxy/proxy/autoclaw-model-config"
    const val PATH_SEND_CODE = "/userapi/v1/agent-send-code"
    const val PATH_LOGIN = "/userapi/v1/agent-login"
    const val PATH_REFRESH = "/userapi/v1/refresh"
    const val PATH_AGENT_REFRESH = "/userapi/v1/agent-refresh"
    const val PATH_WALLETS = "/agent-assetmgr/api/v2/wallets"
    const val PATH_SIGNIN = "/autoclaw-proxy/proxy/autoclaw-task-complete"

    /** 签名盐（随客户端分发，网关管家同款）。 */
    const val SIGN_KEY = "38d2391985e2369a5fb8227d8e6cd5e5"
    const val SIGN_APPID = "100003"
    const val SOURCE_ID = "autoclaw"

    /** JWT 剩余寿命低于该值（秒）即触发刷新。 */
    const val REFRESH_MARGIN_SEC = 300L

    /** 服务端模型目录缓存 TTL。 */
    const val REMOTE_MODEL_TTL_MS = 10 * 60_000L

    const val CONNECT_TIMEOUT_MS = 20_000
    const val JSON_READ_TIMEOUT_MS = 30_000

    /** 对话读超时：长任务可以跑很久，与参考实现一致给 10 分钟。 */
    const val CHAT_READ_TIMEOUT_MS = 600_000

    /** 桌面 Chrome UA（autoclaw 桌面端指纹；裸 OkHttp UA 会撞 406）。 */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"
}
