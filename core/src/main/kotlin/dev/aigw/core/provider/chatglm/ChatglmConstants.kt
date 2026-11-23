package dev.aigw.core.provider.chatglm

/** 智谱清言（agentmore.chatglm.cn）端点与签名常量。 */
internal object ChatglmConstants {
    const val BASE = "https://agentmore.chatglm.cn"
    const val PATH_REFRESH = "/chatglm/user-api/user/refresh"
    const val PATH_USER_INFO = "/chatglm/user-api/user/info"
    const val PATH_CHECKIN = "/chatglm/member-api/member/daily_login_score"
    const val PATH_CONVERSATION = "/chatglm/agent-api/conversation"
    const val PATH_PROJECT_LIST = "/chatglm/agent-api/project/list?page=1&page_size=30"
    const val PATH_STREAM = "/chatglm/agent-api/stream"
    const val PATH_CUR_TS = "/chatglm/operation-api/config/cur_ts"

    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" +
        " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"

    /** 签名盐（随官方 Web 端分发）。 */
    const val SIGN_KEY = "8a1317a7468aa3ad86e997d08f3f31cb"

    /** project/list 的 agent 缓存 TTL。 */
    const val AGENT_CACHE_TTL_MS = 600_000L

    const val CONNECT_TIMEOUT_MS = 20_000
    const val JSON_READ_TIMEOUT_MS = 20_000

    /** 对话读超时：长任务可以跑很久，与参考实现一致给 10 分钟。 */
    const val CHAT_READ_TIMEOUT_MS = 600_000

    /** workspace 未就绪（10030）重连上限与退避。 */
    const val RECONNECT_MAX = 3
    const val RECONNECT_BACKOFF_BASE_MS = 2_000L

    /** 上游业务码。 */
    const val CODE_WORKSPACE_NOT_READY = 10030
    const val CODE_SERVER_BUSY = 10024
    const val CODE_NO_CREDITS = -1001
    const val CODE_CONVERSATION_DUPLICATE = 40014
}
