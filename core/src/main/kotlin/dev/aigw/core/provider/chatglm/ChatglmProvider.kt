package dev.aigw.core.provider.chatglm

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.CreditUnit
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.requestedModelOf
import dev.aigw.core.util.stringOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 智谱清言（agentmore.chatglm.cn）通道。
 *
 * 协议要点（对齐本地网关 + 智谱清言网关 1.5.9 参考实现）：
 * - agent_id **不是**模型名：从 project/list 解析，拿模型名当 agent_id 上游直接 500；
 * - 对话端点只收一条 user 文本，OpenAI messages 先拼合；
 * - 模型由 selected_model 决定（agent 不分模型）；
 * - 建会话失败不能硬发 stream（上游对无会话请求直接 500）；
 * - 40014 = 同一 conversation 重复提交相同内容：丢缓存重建；
 * - token 刷新走双 rotate：refresh 接口的 Bearer 用的是 refresh_token。
 */
class ChatglmProvider(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val hooks: ProviderHooks = ProviderHooks(),
) : Provider {

    override val id: String = ID
    override val displayName: String = "智谱清言"
    override val authKind: AuthKind = AuthKind.NONE
    override val capabilities: Set<ProviderCapability> =
        setOf(ProviderCapability.CREDIT_REFRESH, ProviderCapability.CHECKIN)

    /** 每账号一个 HTTP 实例（保留服务器时间校准缓存）。 */
    private val https = ConcurrentHashMap<String, ChatglmHttp>()

    /** 模型名 → agent_id 缓存：key "$uid|$model"。 */
    private val agentCache = ConcurrentHashMap<String, Pair<Long, String>>()

    /** agent → conversation_id 缓存：key "$uid|$agentId"（对齐本地网关，永久复用）。 */
    private val conversationCache = ConcurrentHashMap<String, String>()

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        // 无账号不展示模型：没有账号支撑的目录点进去必然调用失败
        // （与 Trae/Loomy 等既有供应商的「无账号 → 空目录」约定对齐）
        if (account == null) {
            return ProviderModelCatalogView(models = emptyList(), fromFallback = false, error = "")
        }
        return ProviderModelCatalogView(models = ChatglmModels.advertised(), fromFallback = false, error = "")
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim()
        if (model.isEmpty() || model == "auto") return ChatglmModels.DEFAULT_MODEL
        val prefix = "$ID/"
        val stripped = if (model.startsWith(prefix, ignoreCase = true)) {
            model.substring(prefix.length).trim()
        } else {
            model
        }
        return stripped.ifEmpty { ChatglmModels.DEFAULT_MODEL }
    }

    override fun hosts(): List<String> = listOf("chatglm.cn", "chatglm.com")

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val parsed = parseOrNull(account) ?: return FailedChatCall(401, "智谱清言账号凭证无法解析")
        val http = httpFor(parsed)
        val requested = resolveModel(requestedModelOf(openAiBody))
        val body = runCatching { JsonParser.parseString(openAiBody).asJsonObject }
            .getOrElse { return FailedChatCall(400, "请求体不是合法 JSON：${it.message}") }

        // 推理档位：客户端显式 reasoning_effort 覆盖，归一到上游三档
        var effort = "high"
        val re = body.stringOrNull("reasoning_effort").orEmpty().lowercase()
        if (re.isNotEmpty()) {
            effort = when (re) {
                "low", "minimal", "none" -> "low"
                "medium" -> "high"
                "high", "max" -> "max"
                else -> "high"
            }
        }

        val agentId = resolveAgent(account, parsed, requested)
            ?: return FailedChatCall(404, "模型目录未命中智能体：$requested（账号可能没有可用智能体）")
        val prompt = mergePrompt(body.getAsJsonArray("messages"))

        // 会话策略：**按 agent 复用缓存 conversation（对齐本地网关 GatewayServer）**。
        // 本地网关（已实测不复读）同样复用会话 + 全量历史合并成一条 prompt，
        // 所以「复读」的根因不是会话复用。恰恰相反：每请求新建 fresh 会话会触发
        // 上游的全量上下文重推（每次 logic_id 都不同，per-part 前缀差量拦不住，
        // 重推内容被当增量追加，正文成倍重复——截图实证）。
        // 只在缓存为空时调 /conversation 新建；首帧回传的 conversation_id 回写缓存。
        val convKey = "${account.uid}|$agentId"
        var conversationId = conversationCache[convKey].orEmpty()
        if (conversationId.isEmpty()) {
            conversationId = createConversation(http, parsed, agentId)
                ?: return FailedChatCall(502, "智谱建会话失败，请稍后重试或重新导入凭证")
            conversationCache[convKey] = conversationId
        }

        val payload = JsonObject().apply {
            addProperty("agent_id", agentId)
            add("meta_data", JsonObject().apply {
                addProperty("platform", "pc")
                addProperty("reasoning_effort", effort)
                addProperty("selected_model", ChatglmModels.selectedModel(requested))
            })
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", prompt)
                        })
                    })
                })
            })
            addProperty("conversation_id", conversationId)
        }

        val chatId = "chatcmpl-" + java.lang.Long.toHexString(nowMillis())
        var result = http.openStream(parsed.token, payload.toString())
        if (result.status == 401 || result.status == 403) {
            result.close()
            val refreshed = refreshToken(account, parsed, http)
            if (refreshed != null) {
                result = http.openStream(refreshed.token, payload.toString())
            }
            if (result.status == 401 || result.status == 403) {
                result.close()
                return FailedChatCall(401, "智谱清言登录态已失效，请重新导入凭证")
            }
        }
        if (!result.isOk) {
            val errBody = result.errorBody
            hooks.onLog("智谱对话失败（HTTP ${result.status}）：${errBody.take(300)}")
            result.close()
            return FailedChatCall(result.status, errBody)
        }

        val stream = ChatglmSseStream(
            first = result,
            chatId = chatId,
            model = requested,
            basePayload = payload,
            reopen = { p -> http.openStream(parsed.token, p) },
            newConversation = { createConversation(http, parsed, agentId) },
            // 首帧回传的 conversation_id 回写缓存，供后续请求复用（对齐本地网关）
            onConversationId = { cid ->
                if (cid.isNotEmpty()) conversationCache[convKey] = cid
            },
            // 原始帧采样 + 防复读触发都走网关日志，复现时可直接定位
            onDebugLog = { msg -> hooks.onLog(msg) },
        )
        if (isStreamingBody(openAiBody)) {
            return StreamingChatCall(200, stream) { stream.close() }
        }
        val aggregated = try {
            OpenAiSseAggregator.aggregate(stream, requested)
        } catch (e: Exception) {
            stream.close()
            return FailedChatCall(502, "聚合流式响应失败：${e.message}")
        }
        stream.close()
        return AggregatedChatCallCompat(aggregated)
    }

    override fun classify(status: Int, body: String): UpstreamError =
        ChatglmErrors.classify(status, body)

    // ------------------------------------------------------------------ agent 解析

    /**
     * 模型名 → agent_id（24 位 ObjectId）。
     *
     * 从 project/list 取 meta_data.type == "agent" 的条目，
     * agentId = agent_info.id ?: relate_id ?: agent_id（依次取首个非空）；
     * title / agent_info.name 与请求模型名匹配，未命中用第一个智能体
     * （上游账号通常只有一个默认智能体）。
     */
    private fun resolveAgent(account: ProviderAccount, parsed: ChatglmAccount, model: String): String? {
        val key = "${account.uid}|$model"
        val cached = agentCache[key]
        if (cached != null && nowMillis() - cached.first < ChatglmConstants.AGENT_CACHE_TTL_MS) {
            return cached.second.ifEmpty { null }
        }
        val http = httpFor(parsed)
        var res = http.json(ChatglmConstants.PATH_PROJECT_LIST, "GET")
        if (res.status == 401 || res.status == 403) {
            val refreshed = refreshToken(account, parsed, http)
            if (refreshed != null) res = http.json(ChatglmConstants.PATH_PROJECT_LIST, "GET")
        }
        if (!res.isOk) {
            hooks.onLog("智谱智能体列表失败 HTTP ${res.status}：${res.body.take(200)}")
            return null
        }
        val env = runCatching { JsonParser.parseString(res.body).asJsonObject }.getOrNull()
        val status = env?.get("status")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
        val payload = env?.getAsJsonObject("result") ?: env?.getAsJsonObject("data")
        if ((status != 0 && status != 200) || payload == null) {
            hooks.onLog("智谱智能体列表结构异常：${res.body.take(200)}")
            return null
        }
        val list = payload.getAsJsonArray("list")
        var found = ""
        if (list != null) {
            val nModel = ChatglmModels.normalizeKey(model)
            for (element in list) {
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val meta = item.getAsJsonObject("meta_data")
                // 只要智能体条目，其余（showcase / group）跳过
                if (meta?.str("type") != "agent") continue
                val info = item.getAsJsonObject("agent_info")
                val infoId = info?.str("id")?.trim().orEmpty()
                val relateId = item.str("relate_id").trim()
                val rawAgentId = item.str("agent_id").trim()
                val id = infoId.ifEmpty { relateId.ifEmpty { rawAgentId } }
                if (id.isEmpty()) continue
                if (found.isEmpty()) found = id
                val name = info?.str("name")?.trim().orEmpty()
                val title = item.str("title").trim()
                if ((name.isNotEmpty() && ChatglmModels.normalizeKey(name) == nModel) ||
                    (title.isNotEmpty() && ChatglmModels.normalizeKey(title) == nModel)
                ) {
                    found = id
                    break
                }
            }
        }
        agentCache[key] = nowMillis() to found
        return found.ifEmpty { null }
    }

    /** POST /conversation 建 agent 会话；失败返回 null。 */
    private fun createConversation(
        http: ChatglmHttp,
        parsed: ChatglmAccount,
        agentId: String,
    ): String? {
        var res = http.json(
            ChatglmConstants.PATH_CONVERSATION,
            "POST",
            body = "{\"agent_id\":\"$agentId\"}",
        )
        if (res.status == 401 || res.status == 403) {
            if (refreshTokenInternal(parsed, http) != null) {
                res = http.json(
                    ChatglmConstants.PATH_CONVERSATION,
                    "POST",
                    body = "{\"agent_id\":\"$agentId\"}",
                )
            }
        }
        if (!res.isOk) {
            hooks.onLog("智谱建会话失败 HTTP ${res.status}：${res.body.take(200)}")
            return null
        }
        val env = runCatching { JsonParser.parseString(res.body).asJsonObject }.getOrNull()
        val status = env?.get("status")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
        val result = env?.getAsJsonObject("result")
        if ((status == 0 || status == 200) && result != null) {
            val cid = result.str("conversation_id").trim()
            if (cid.isNotEmpty()) return cid
        }
        hooks.onLog("智谱建会话未取得 conversation_id：${res.body.take(200)}")
        return null
    }

    // ------------------------------------------------------------------ 凭证

    override fun importCredentials(raw: String): ProviderAccount? {
        val parsed = parseImport(raw) ?: throw IllegalStateException(
            "无法识别凭证：需要含 chatglm_token 的 cookie，或 {token, refreshToken, deviceId} JSON",
        )
        // 站点对未登录访客也下发匿名 chatglm_token cookie：必须验活，
        // 否则会导入一个实际不可用的空账号
        val http = ChatglmHttp(parsed)
        val info = http.json(ChatglmConstants.PATH_USER_INFO, "GET")
        if (!info.isOk) {
            throw IllegalStateException("凭证验证失败（HTTP ${info.status}）：${info.body.take(200)}")
        }
        val member = runCatching {
            JsonParser.parseString(info.body).asJsonObject
                .getAsJsonObject("result")?.getAsJsonObject("member_info")
        }.getOrNull()
        val leftScore = member?.get("left_score")?.takeIf { it.isJsonPrimitive }?.asDouble
        if (member == null || leftScore == null || leftScore < 0) {
            throw IllegalStateException("凭证不是有效登录态（未取到 member_info.left_score）")
        }
        // uid 取不到时用 token 摘要，避免同一凭证重复导入生成两条记录
        val uid = member.str("user_id").ifEmpty {
            "token-" + Integer.toHexString(parsed.token.hashCode())
        }
        val secret = parsed.copy(uid = uid).toSecret()
        return ProviderAccount(ID, uid, uid, secret)
    }

    /** 双 rotate 刷新：Bearer 用 refresh_token（非 accessToken），空 body。 */
    private fun refreshTokenInternal(parsed: ChatglmAccount, http: ChatglmHttp): ChatglmAccount? {
        if (parsed.refreshToken.isEmpty()) return null
        val res = http.json(ChatglmConstants.PATH_REFRESH, "POST", bearer = parsed.refreshToken, body = "{}")
        if (!res.isOk) {
            hooks.onLog("智谱 token 刷新失败 HTTP ${res.status}：${res.body.take(200)}")
            return null
        }
        val result = runCatching {
            JsonParser.parseString(res.body).asJsonObject.getAsJsonObject("result")
        }.getOrNull()
        val access = result?.str("access_token").orEmpty()
        if (access.isEmpty()) return null
        val refresh = result?.str("refresh_token").orEmpty()
        return parsed.withTokens(access, refresh)
    }

    /** 刷新并持久化到账号池。 */
    private fun refreshToken(
        account: ProviderAccount,
        parsed: ChatglmAccount,
        http: ChatglmHttp,
    ): ChatglmAccount? {
        val fresh = refreshTokenInternal(parsed, http) ?: return null
        hooks.onAccountUpdated(account.copy(secret = fresh.toSecret()))
        hooks.onLog("智谱清言令牌已刷新")
        return fresh
    }

    // ------------------------------------------------------------------ 额度 / 签到

    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val parsed = parseOrNull(account) ?: return null
        val http = httpFor(parsed)
        var res = http.json(ChatglmConstants.PATH_USER_INFO, "GET")
        if (res.status == 401 || res.status == 403) {
            val refreshed = refreshToken(account, parsed, http)
            if (refreshed != null) res = http.json(ChatglmConstants.PATH_USER_INFO, "GET")
        }
        if (!res.isOk) {
            return CreditInfo(balance = 0, known = false, detail = "HTTP ${res.status}")
        }
        val member = runCatching {
            JsonParser.parseString(res.body).asJsonObject
                .getAsJsonObject("result")?.getAsJsonObject("member_info")
        }.getOrNull()
        val score = member?.get("left_score")?.takeIf { it.isJsonPrimitive }?.asDouble
        return if (score != null && score >= 0) {
            CreditInfo(balance = Math.round(score), unit = CreditUnit.CREDITS)
        } else {
            CreditInfo(balance = 0, known = false, detail = "响应缺少 left_score")
        }
    }

    override fun performAction(
        account: ProviderAccount,
        action: String,
        payload: JsonObject,
    ): ProviderActionResult = when (action) {
        ACTION_CHECKIN -> checkin(account)
        else -> ProviderActionResult.unsupported(action)
    }

    private fun checkin(account: ProviderAccount): ProviderActionResult {
        val parsed = parseOrNull(account) ?: return ProviderActionResult.failure("账号凭证无法解析")
        val http = httpFor(parsed)
        var res = http.json(ChatglmConstants.PATH_CHECKIN, "POST", body = "{}")
        if (res.status == 401 || res.status == 403) {
            val refreshed = refreshToken(account, parsed, http)
            if (refreshed != null) res = http.json(ChatglmConstants.PATH_CHECKIN, "POST", body = "{}")
        }
        if (!res.isOk) {
            return ProviderActionResult.failure("签到失败（HTTP ${res.status}）：${res.body.take(160)}")
        }
        return ProviderActionResult.success("签到请求已完成：${res.body.take(120)}")
    }

    // ------------------------------------------------------------------ 工具

    private fun httpFor(parsed: ChatglmAccount): ChatglmHttp =
        https.getOrPut(parsed.uid) { ChatglmHttp(parsed) }

    private fun parseOrNull(account: ProviderAccount): ChatglmAccount? =
        runCatching { ChatglmAccount.parse(account.secret) }.getOrNull()

    /** 接受 cookie 串或 JSON 导入。 */
    private fun parseImport(raw: String): ChatglmAccount? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("{")) {
            val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                ?: return null
            val token = obj.str("token").ifEmpty {
                obj.str("accessToken").ifEmpty { obj.str("chatglm_token") }
            }
            if (token.isEmpty()) return null
            return ChatglmAccount(
                uid = "",
                token = token,
                refreshToken = obj.str("refreshToken").ifEmpty { obj.str("refresh_token") },
                deviceId = obj.str("deviceId").ifEmpty { obj.str("deid") },
            )
        }
        // cookie 形态：chatglm_token=...; chatglm_refresh_token=...
        var token = ""
        var refresh = ""
        for (part in text.split(";")) {
            val p = part.trim()
            if (p.startsWith("chatglm_token=")) {
                token = p.substring("chatglm_token=".length)
            } else if (p.startsWith("chatglm_refresh_token=")) {
                refresh = p.substring("chatglm_refresh_token=".length)
            }
        }
        if (token.isEmpty()) return null
        return ChatglmAccount(uid = "", token = token, refreshToken = refresh)
    }

    /** OpenAI messages 拼合为单一 prompt（上游仅收一条 user 文本）。 */
    private fun mergePrompt(messages: JsonArray?): String {
        val sb = StringBuilder()
        if (messages != null) {
            for (element in messages) {
                val m = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val role = m.str("role").ifEmpty { "user" }
                val text = textOfContent(m.get("content"))
                if (text.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append("\n\n")
                if (role.equals("user", true)) {
                    sb.append(text)
                } else {
                    sb.append('[').append(role).append("] ").append(text)
                }
            }
        }
        return sb.toString()
    }

    /** content 取文本：String 或 parts[].text（对齐 WorkBuddyCore.textOfContent）。 */
    private fun textOfContent(content: com.google.gson.JsonElement?): String {
        if (content == null || content.isJsonNull) return ""
        if (content.isJsonPrimitive) return content.asString
        if (content.isJsonArray) {
            val sb = StringBuilder()
            for (element in content.asJsonArray) {
                val part = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val t = part.str("text")
                if (t.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(t)
                }
            }
            return sb.toString()
        }
        return ""
    }

    /** 非流式结果的最小 ChatCall 实现（provider 内已聚合完毕）。 */
    private class AggregatedChatCallCompat(override val aggregated: String) : ChatCall {
        override val status: Int get() = 200
        override val errorBody: String get() = ""
        override val stream: java.io.InputStream? get() = null
        override fun close() = Unit
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    companion object {
        const val ID = "chatglm"
    }
}
