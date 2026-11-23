package dev.aigw.core.provider.dumate

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.CreditUnit
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.requestedModelOf
import dev.aigw.core.util.firstLong
import dev.aigw.core.util.firstString
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * 百度搭子（DuMate）网页链路通道。
 *
 * 为什么只做网页链路：桌面链路要读 `%APPDATA%\qianfan-desktop-app\auth.json` 并连
 * 本地 `127.0.0.1:8980` 的 Go 后端，那是 Windows 专有的；网页链路的全部依赖
 * 只是一个浏览器 cookie，Android 上完全可复现，且首字延迟更低（少了本地中转一跳）。
 *
 * 协议事实（对上游实测得到，不是推断）：
 * 1. `GET  {WEB_BASE}/api/dumate/app/token/get`，带 cookie → `{result:{token,expired_at}}`
 * 2. `POST https://{GATEWAY_HOST}/gateway/apis/v1/chat/completions`，带 `Authorization: Bearer <token>`
 *    —— 这个端点**本身就是 OpenAI 兼容协议**，所以这里不做协议改写，只补认证头
 * 3. 上游用 HTTP 200 + `code=10001` 表示登录态失效，必须按业务码判定，不能只看状态码
 * 4. 别名（`model-text` / `glm-5`）落到哪个真实后端由上游决定，响应头 `X-Dumate-Gw-Realmodel` 回传
 */
class DumateProvider(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val hooks: ProviderHooks = ProviderHooks(),
    /** 端点覆盖：测试指向本地假上游用；生产走官方域名。 */
    endpoints: DumateEndpoints = DumateEndpoints(),
) : Provider {

    private val http = DumateHttp(endpoints)

    override val id: String = ID
    override val displayName: String = "百度搭子"
    override val authKind: AuthKind = AuthKind.NONE
    override val capabilities: Set<ProviderCapability> =
        setOf(ProviderCapability.CREDIT_REFRESH, ProviderCapability.CHECKIN)

    /**
     * 运行态 token 缓存，**按账号隔离**。
     *
     * 不能用一个全局变量：多账号池里 A 的 token 被写到 B 上，会表现为
     * 「两个账号随机 401」。键用 cookie 的摘要而不是 uid —— 导入时 uid 可能还没解析出来。
     */
    private class TokenCache(val token: String, val expiresAtMillis: Long)

    private val tokenCache = ConcurrentHashMap<String, TokenCache>()

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        // 无账号不展示模型：目录没有账号支撑，点进去必然调用失败，列出来只会误导
        // （与 Trae/Loomy 等既有供应商的「无账号 → 空目录」约定对齐）
        if (account == null) {
            return ProviderModelCatalogView(models = emptyList(), fromFallback = false, error = "")
        }
        return ProviderModelCatalogView(
            models = DumateModelCatalog.models(),
            fromFallback = false,
            // 上游没有模型列表接口，这不是「拉取失败」而是「本来就没有」——
            // 报错会让人以为是网络问题
            error = "",
        )
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim()
        if (model.isEmpty() || model == "auto") return DumateModelCatalog.DEFAULT_MODEL
        // **只在确实带 `dumate/` 前缀时才剥**。不能用 substringAfter('/') 一刀切：
        // `dm-auto-model/text.L0` 里的斜杠是档位编码的一部分，剥掉会把模型名弄成
        // `text.L0`，上游一律 404/400，而界面上看不出任何异常。
        val prefix = "$ID/"
        val stripped = if (model.startsWith(prefix, ignoreCase = true)) {
            model.substring(prefix.length).trim()
        } else {
            model
        }
        return stripped.ifEmpty { DumateModelCatalog.DEFAULT_MODEL }
    }

    override fun hosts(): List<String> = listOf("dumate.cn", "baidu.com")

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val parsed = parseOrNull(account) ?: return FailedChatCall(401, "搭子账号凭证无法解析")
        val model = resolveModel(requestedModelOf(openAiBody))
        val streaming = isStreamingBody(openAiBody)
        // max_tokens 按模型钳到上游允许的上界：云网关按模型转给不同后端，各家范围不同，
        // 客户端填大了会直接 400，而错误文案是上游原话，用户看不出是自己填大了
        val body = clampMaxTokens(openAiBody, model)

        // 1) 取 token（缓存优先，临期才打上游）
        var token = validToken(parsed) ?: return FailedChatCall(
            401,
            "百度搭子 cookie 已失效，需要重新登录（重新复制一次 cookie）",
        )

        // 2) 发请求；401 时强刷一次 token 重试一次——token 可能在本地过期时间
        //    之前被服务端作废（同账号在别处重新登录会顶掉旧票）
        var result = http.openChat(token, body, streaming)
        if (result.status == 401) {
            result.close()
            token = forceRefreshToken(parsed) ?: return FailedChatCall(
                401,
                "百度搭子 cookie 已失效，需要重新登录（重新复制一次 cookie）",
            )
            result = http.openChat(token, body, streaming)
        }

        // 3) 非 2xx：分类后交给网关决定换号还是禁用
        if (!result.isOk) {
            val (kind, message) = DumateErrors.classify(result.status, result.errorBody)
            // 400 里可能带 max_tokens 范围声明，顺手学下来（认不出就丢弃，不影响转发）
            if (result.status == 400) {
                DumateModelCaps.learn(model, result.errorBody)?.let {
                    hooks.onLog("学到 ${model} 的 max_tokens 上界：$it")
                }
            }
            hooks.onLog("搭子对话失败（HTTP ${result.status}，$kind）：${result.errorBody.take(300)}")
            result.close()
            if (kind == ErrorKind.SESSION_DEAD) {
                hooks.onAccountError(id, account.uid, UpstreamError(kind, message))
            }
            return FailedChatCall(DumateErrors.httpStatusOf(result.status, kind), result.errorBody)
        }

        if (result.realModel.isNotEmpty()) {
            hooks.onLog("搭子模型 $model → 上游实际 ${result.realModel}")
        }

        val stream = result.stream ?: run {
            result.close()
            return FailedChatCall(502, "上游没有返回响应体")
        }

        // 4) 判断是不是 SSE。必须把探测读掉的字节接回去，否则客户端会丢掉开头
        val probe = stream.peekHead()
        if (!probe.isSse) {
            // 必须读 probe.stream（已把探测读掉的字节接回去），读原流会丢掉开头 64 字节——
            // 短错误体（如 `{"code":10001}`）会整条消失，表现为「上游返回了空响应体」
            val text = runCatching { probe.stream.use { it.readBytes().toString(StandardCharsets.UTF_8) } }
                .getOrDefault("")
            result.close()
            if (text.isBlank()) return FailedChatCall(502, "上游返回了空响应体")
            // HTTP 200 + 业务错误码（上游对失效 token 有时就这么回）
            val code = DumateErrors.codeOf(text)
            if (code != null && code != 0L) {
                val (kind, message) = DumateErrors.classify(200, text)
                if (kind == ErrorKind.SESSION_DEAD) {
                    hooks.onAccountError(id, account.uid, UpstreamError(kind, message, code))
                }
                return StreamFailureChatCall(
                    DumateErrors.httpStatusOf(200, kind),
                    UpstreamError(kind, message, code),
                )
            }
            return AggregatedChatCall(200, text)
        }

        val full = probe.stream
        if (streaming) {
            return StreamingChatCall(200, full) { result.close() }
        }
        // 客户端要非流式、上游却给了 SSE：本地聚合（不能把 SSE 当 JSON 透传）
        val aggregated = try {
            OpenAiSseAggregator.aggregate(full, model)
        } catch (e: Exception) {
            result.close()
            return FailedChatCall(502, "聚合流式响应失败：${e.message}")
        }
        result.close()
        return AggregatedChatCall(200, aggregated)
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val (kind, message) = DumateErrors.classify(status, body)
        return UpstreamError(kind, message, DumateErrors.codeOf(body) ?: 0L)
    }

    // ------------------------------------------------------------------ 凭证

    /**
     * 预刷新：只在缓存 token 临期时动作。
     *
     * 网关每个请求前都会调它，实现必须**廉价**——每次都打上游会让每个请求
     * 多一次往返，直接体现在首字延迟上。
     */
    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
        val parsed = parseOrNull(account) ?: return null
        // 内存缓存还有效、且与账号里存的一致 → 无需动作
        val cached = tokenCache[cacheKey(parsed)]
        if (cached != null && cached.expiresAtMillis - DumateConstants.REFRESH_SKEW_MS > nowMillis()) {
            if (parsed.token == cached.token) return null
            return account.copySecret(parsed.copy(
                token = cached.token,
                tokenExpiresAtMillis = cached.expiresAtMillis,
            ))
        }
        // 账号里存的 token 还有效 → 无需动作
        if (parsed.tokenUsable(nowMillis())) return null
        val fresh = forceRefreshToken(parsed) ?: return null
        val entry = tokenCache[cacheKey(parsed)] ?: return null
        return account.copySecret(parsed.copy(
            token = fresh,
            tokenExpiresAtMillis = entry.expiresAtMillis,
        ))
    }

    /**
     * 粘贴导入：接受 cookie 串，或包含 cookie 的 JSON。
     *
     * 不做「看起来像不像 cookie」的格式校验——百度 cookie 的组成会变，猜错格式
     * 只会让用户拿着一份合法凭证却导不进去。真正的问题是「这个 cookie 能不能换到
     * token」，那一步如实回答：拿不到就抛错，把上游原话带给用户。
     */
    override fun importCredentials(raw: String): ProviderAccount? {
        val cookie = normalizeCookie(raw) ?: return null
        val tokenResult = fetchToken(cookie)
        if (!tokenResult.ok) throw IllegalStateException(tokenResult.error)
        return accountOf(cookie, tokenResult.token, tokenResult.expiresAtMillis)
    }

    /**
     * 用已知可用的 cookie + token 直接产出账号。
     *
     * 给登录流程用：那边已经拿 cookie 换过一次 token 验活，再走 [importCredentials]
     * 会白打一次同样的接口（还多一次失败面）。uid/昵称仍走用户信息接口补全。
     */
    fun accountOf(cookie: String, token: String, expiresAtMillis: Long): ProviderAccount {
        val info = http.webJson(DumateConstants.PATH_USER_INFO, "GET", cookie)
        var uid = ""
        var nickname = ""
        if (info.isOk) {
            val payload = unwrap(info.body)
            if (payload != null) {
                uid = payload.firstString("uid", "userId", "user_id")
                nickname = payload.firstString("nickname", "displayName", "name", "userName")
            }
        }
        // token 能换到就说明 cookie 是活的；uid 拿不到时用 cookie 摘要当标识，
        // 避免同一账号重复导入生成两条记录
        if (uid.isEmpty()) uid = "cookie-" + cookie.hashCode().toUInt().toString(16)

        val account = DumateAccount(
            uid = uid,
            cookie = cookie,
            nickname = nickname,
            token = token,
            tokenExpiresAtMillis = expiresAtMillis,
            createdAtMillis = nowMillis(),
        )
        return ProviderAccount(id, account.uid, account.nickname, account.toSecret())
    }

    // ------------------------------------------------------------------ 积分 / 签到

    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val parsed = parseOrNull(account) ?: return null
        val (res, status) = http.webJsonWithStatus(DumateConstants.PATH_QUOTA_OVERVIEW, "GET", parsed.cookie)
        // 上游字段会变，出问题时要能一眼看到「它到底回了什么」——日志里留原始响应（截断）
        hooks.onLog("搭子额度接口（HTTP $status）：${res.body.take(600)}")
        if (!res.isOk) {
            return CreditInfo(balance = 0, known = false, detail = DumateErrors.messageOf(res.body).ifEmpty { "HTTP $status" })
        }
        // 积分是**带小数的字符串**（"28700.00"），直接 asLong 会抛异常被吞成 0，
        // 表现成「总 0 / 已用 0」但账号其实是好的。解析细节见 DumateCredits
        return DumateCredits.toCreditInfo(DumateCredits.parse(res.body), res.body)
    }

    override fun performAction(
        account: ProviderAccount,
        action: String,
        payload: JsonObject,
    ): ProviderActionResult = when (action) {
        ACTION_CHECKIN -> checkin(account)
        else -> ProviderActionResult.unsupported(action)
    }

    /** 每日签到（幂等：当天已签上游会如实回「已领取」）。 */
    private fun checkin(account: ProviderAccount): ProviderActionResult {
        val parsed = parseOrNull(account) ?: return ProviderActionResult.failure("账号凭证无法解析")
        val res = http.webJson(DumateConstants.PATH_LOGIN_BONUS, "POST", parsed.cookie, body = "{}")
        // 签到返回里才有「今天给了多少分」，出问题时要能看到原始响应
        hooks.onLog("搭子签到接口（HTTP ${res.status}）：${res.body.take(600)}")
        if (!res.isOk) {
            val (kind, message) = DumateErrors.classify(res.status, res.body)
            if (kind == ErrorKind.SESSION_DEAD) {
                hooks.onAccountError(id, account.uid, UpstreamError(kind, message))
            }
            return ProviderActionResult.failure(message)
        }
        val payload = unwrap(res.body)
        val points = payload?.firstLong("points", "rewardPoints", "gainPoints", "totalPoints", "todayPoints") ?: 0L
        return ProviderActionResult.success(
            if (points > 0) "签到成功，获得 $points 积分" else "签到请求已完成",
        )
    }

    // ------------------------------------------------------------------ 内部

    private fun parseOrNull(account: ProviderAccount): DumateAccount? =
        runCatching { DumateAccount.parse(account.secret) }.getOrNull()

    /** 缓存键：cookie 摘要（uid 在导入早期可能为空）。 */
    private fun cacheKey(account: DumateAccount): String =
        account.cookie.hashCode().toUInt().toString(16)

    /** 取一个可用的 token：内存缓存 → 账号里存的 → 打上游换。全部失败返回 null。 */
    private fun validToken(account: DumateAccount): String? {
        val key = cacheKey(account)
        val cached = tokenCache[key]
        if (cached != null && cached.expiresAtMillis - DumateConstants.REFRESH_SKEW_MS > nowMillis()) {
            return cached.token
        }
        if (account.tokenUsable(nowMillis())) {
            tokenCache[key] = TokenCache(account.token, account.tokenExpiresAtMillis)
            return account.token
        }
        return forceRefreshToken(account)
    }

    private class TokenResult(
        val ok: Boolean,
        val token: String = "",
        val expiresAtMillis: Long = 0L,
        val error: String = "",
    )

    /** 强刷 token（失败返回 null，原因进日志）。 */
    private fun forceRefreshToken(account: DumateAccount): String? {
        val result = fetchToken(account.cookie)
        if (!result.ok) {
            hooks.onLog("搭子取 token 失败：${result.error}")
            return null
        }
        tokenCache[cacheKey(account)] = TokenCache(result.token, result.expiresAtMillis)
        hooks.onLog("搭子 token 已刷新，有效期至 ${result.expiresAtMillis}")
        return result.token
    }

    /** 登录流程用：拿 cookie 换 token，成功回 (token, 过期时间)，失败回 null（原因记日志）。 */
    fun fetchTokenForLogin(cookie: String): Pair<String, Long>? {
        val result = fetchToken(cookie)
        if (!result.ok) {
            hooks.onLog("搭子 token 换取失败：${result.error}")
            return null
        }
        return result.token to result.expiresAtMillis
    }

    private fun fetchToken(cookie: String): TokenResult {
        val res = http.webJson(
            DumateConstants.PATH_TOKEN,
            "GET",
            cookie,
            readTimeoutMs = DumateConstants.TOKEN_TIMEOUT_MS,
        )
        if (!res.isOk) {
            val code = DumateErrors.codeOf(res.body)
            val expired = res.status == 401 || DumateErrors.isSessionDeadCode(code)
            val message = DumateErrors.messageOf(res.body).ifEmpty {
                if (res.status == 0) "网络连接失败：${res.body.take(120)}" else "HTTP ${res.status}"
            }
            return TokenResult(
                false,
                error = if (expired) "百度搭子 cookie 已失效，请重新登录后再复制 cookie" else message,
            )
        }
        val payload = unwrap(res.body)
            ?: return TokenResult(false, error = "上游返回的不是 JSON：${res.body.take(120)}")
        val token = payload.firstString("token", "accessToken", "access_token")
        if (token.isEmpty()) {
            return TokenResult(false, error = "上游没有返回 token：${res.body.take(160)}")
        }
        // expired_at 是 ISO 时间；解析失败按 50 分钟兜底（宁可早刷不要晚刷）
        val expiresAt = payload.firstLong("expired_at", "expiredAt", "expireAt", "expiresAt")
        val resolved = if (expiresAt > 0) expiresAt else nowMillis() + DumateConstants.FALLBACK_TOKEN_TTL_MS
        return TokenResult(true, token, resolved)
    }

    /** 上游信封不统一：优先 `result`，再 `data`，最后顶层。 */
    private fun unwrap(body: String): JsonObject? {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        return obj.objOrNull("result") ?: obj.objOrNull("data") ?: obj
    }

    private fun ProviderAccount.copySecret(account: DumateAccount): ProviderAccount =
        ProviderAccount(providerId, account.uid.ifEmpty { uid }, account.nickname, account.toSecret())

    /** 探测读掉的头部字节必须接回去，否则流式响应的开头会被吞掉。 */
    private class PeekedStream(val stream: InputStream, val isSse: Boolean)

    private fun InputStream.peekHead(): PeekedStream {
        val head = ByteArray(64)
        val n = runCatching { read(head) }.getOrDefault(-1)
        if (n <= 0) return PeekedStream(this, false)
        val text = String(head, 0, n, StandardCharsets.UTF_8)
        // 放宽到 SSE 的任何合法开头：注释心跳（`: ping`）、事件名（`event:`）、
        // 消息 id（`id:`）都算流——只认 `data:` 会把带心跳的流误判成非流式，
        // 结果客户端要等整条回答生成完才看到第一个字
        val sse = text.startsWith("data:") || text.startsWith(":") || text.contains("event:") ||
            text.startsWith("id:") || text.contains("\ndata:")
        return PeekedStream(SequenceInputStream(ByteArrayInputStream(head, 0, n), this), sse)
    }

    companion object {
        const val ID = "dumate"

        /**
         * 规范化粘贴进来的 cookie。
         *
         * 用户会从浏览器开发者工具复制出各种形态：整条 `Cookie:` 头、带换行的多行
         * 键值对、或者包在 JSON 里的 `{"cookie":"..."}`。统一成单行 `k=v; k=v`。
         */
        /**
         * 把请求体里的 `max_tokens` 钳到该模型的上界。
         *
         * 只在**确实超限**时改写：没写 `max_tokens` 或不超限时原样返回同一串，
         * 避免为了钳制把整个 body 重新序列化一遍（会打乱字段顺序、丢掉未知字段的精度）。
         */
        fun clampMaxTokens(openAiBody: String, model: String): String {
            val obj = runCatching { JsonParser.parseString(openAiBody).asJsonObject }.getOrNull()
                ?: return openAiBody
            val raw = obj.get("max_tokens") ?: return openAiBody
            if (!raw.isJsonPrimitive) return openAiBody
            val requested = runCatching { raw.asLong }.getOrNull() ?: return openAiBody
            val clamped = DumateModelCaps.clamp(model, requested)
            if (clamped == requested) return openAiBody
            obj.addProperty("max_tokens", clamped)
            return obj.toString()
        }

        fun normalizeCookie(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("{")) {
                val obj = runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull()
                val inner = obj?.str("cookie")?.trim().orEmpty()
                if (inner.isNotEmpty()) return normalizeCookie(inner)
                return null
            }
            val body = trimmed.removePrefix("Cookie:").removePrefix("cookie:")
            val pairs = body.split(';', '\n', '\r')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.contains('=') }
            if (pairs.isEmpty()) return null
            return pairs.joinToString("; ")
        }
    }
}
