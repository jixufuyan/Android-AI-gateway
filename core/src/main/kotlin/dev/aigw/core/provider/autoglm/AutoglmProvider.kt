package dev.aigw.core.provider.autoglm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ACTION_CHECKIN
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
import dev.aigw.core.provider.SmsLoginSupport
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.requestedModelOf
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 智谱 AutoGLM（autoclaw）通道。
 *
 * 移植自本地网关 AutoGLMClient.java（网关管家 3.4.10 逆向）：
 * - 短信登录（发码 + 验证码登录共用同一 device_id）。
 * - JWT 临期静默刷新（400002 时切 agent-refresh 端点重试）。
 * - 对话是 OpenAI 兼容协议，但认证头是 X-Authorization，且必须过 system 闸门
 *   （竞品身份替换 + OpenClaw 身份前置），否则 406。
 * - 模型路由以服务端 autoclaw-model-config 下发为准，内置表只是兑底。
 */
class AutoglmProvider(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val hooks: ProviderHooks = ProviderHooks(),
) : Provider, SmsLoginSupport {

    override val id: String = ID
    override val displayName: String = "智谱 AutoGLM"
    override val authKind: AuthKind = AuthKind.SMS_CODE
    override val capabilities: Set<ProviderCapability> =
        setOf(ProviderCapability.CREDIT_REFRESH, ProviderCapability.CHECKIN)

    /** 发码/登录/刷新流程中持有的 device_id：同一手机号必须复用同一个。 */
    private val pendingDeviceIds = ConcurrentHashMap<String, String>()

    /** 最近一次登录失败的上游原因（供 UI 弹提示）。 */
    @Volatile
    var lastError: String = ""
        private set

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        // 无账号不展示模型：没有账号支撑的目录点进去必然调用失败
        // （与 Trae/Loomy 等既有供应商的「无账号 → 空目录」约定对齐）
        if (account == null) {
            return ProviderModelCatalogView(models = emptyList(), fromFallback = false, error = "")
        }
        // 有账号时顺手拉一次服务端模型目录（内部有 TTL，不会每刷一次打一次上游）
        refreshModelConfig(account)
        return ProviderModelCatalogView(
            models = AutoglmModels.advertised(),
            fromFallback = AutoglmModels.remoteEmpty(),
            error = "",
        )
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim()
        if (model.isEmpty() || model == "auto") return "auto"
        val prefix = "$ID/"
        val stripped = if (model.startsWith(prefix, ignoreCase = true)) {
            model.substring(prefix.length).trim()
        } else {
            model
        }
        return stripped.ifEmpty { "auto" }
    }

    override fun hosts(): List<String> = listOf("zhipuai.cn", "zhipu.ai")

    /** 拉取服务端模型目录（失败时静默保留内置表）。 */
    private fun refreshModelConfig(account: ProviderAccount) {
        if (AutoglmModels.remoteFresh(nowMillis())) return
        val parsed = parseOrNull(account) ?: return
        val token = ensureFresh(account, parsed) ?: return
        val res = AutoglmHttp.json(AutoglmConstants.PATH_MODEL_CONFIG, "GET", token)
        if (!res.isOk) {
            hooks.onLog("智谱模型目录返回 HTTP ${res.status}")
            return
        }
        if (AutoglmModels.learnRemote(res.body, nowMillis())) {
            hooks.onLog("智谱模型目录已刷新")
        } else {
            hooks.onLog("智谱模型目录结构未识别，继续用内置表")
        }
    }

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val parsed = parseOrNull(account) ?: return FailedChatCall(401, "智谱账号凭证无法解析")
        val token = ensureFresh(account, parsed) ?: return FailedChatCall(
            401,
            "智谱登录态已失效，请重新登录",
        )

        val requested = resolveModel(requestedModelOf(openAiBody))
        val (routeId, bodyModel) = AutoglmModels.route(requested)
        val streaming = isStreamingBody(openAiBody)

        val body = runCatching {
            JsonParser.parseString(openAiBody).asJsonObject
        }.getOrElse { return FailedChatCall(400, "请求体不是合法 JSON：${it.message}") }
        body.addProperty("model", bodyModel)
        // system 闸门：竞品替换 + OpenClaw 身份前置/预置（406 的根源）
        AutoglmIdentity.normalize(body)

        var result = AutoglmHttp.openChat(token, routeId, body.toString())
        if (!result.isOk) {
            val error = AutoglmErrors.classify(result.status, result.errorBody)
            hooks.onLog("智谱对话失败（HTTP ${result.status}，${error.kind}）：${result.errorBody.take(300)}")
            if (error.kind == ErrorKind.SESSION_DEAD) {
                hooks.onAccountError(id, account.uid, error)
            }
            result.close()
            return FailedChatCall(result.status, result.errorBody)
        }

        val stream = result.stream ?: run {
            result.close()
            return FailedChatCall(502, "上游没有返回响应体")
        }

        // 探测首字节判断 SSE 与否；探测读掉的字节必须接回去
        val probe = peekHead(stream)
        if (!probe.isSse) {
            val text = runCatching {
                probe.stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            }.getOrDefault("")
            result.close()
            if (text.isBlank()) return FailedChatCall(502, "上游返回了空响应体")
            // 上游偶发 HTTP 200 + JSON 业务错误码；也可能返回纯文本，解析失败按原文透传
            val code = runCatching {
                com.google.gson.JsonParser.parseString(text).asJsonObject
                    .get("code")?.takeIf { it.isJsonPrimitive }?.asLong
            }.getOrNull()
            return if (code != null && code != 0L) {
                val message = AutoglmErrors.codeMessage(code, AutoglmErrors.messageOf(text))
                StreamFailureChatCall(502, UpstreamError(ErrorKind.CLIENT, message, code))
            } else {
                AggregatedCompat(text)
            }
        }

        val full = probe.stream
        if (streaming) return StreamingChatCall(200, full) { result.close() }
        val aggregated = try {
            OpenAiSseAggregator.aggregate(full, bodyModel)
        } catch (e: Exception) {
            result.close()
            return FailedChatCall(502, "聚合流式响应失败：${e.message}")
        }
        result.close()
        return AggregatedCompat(aggregated)
    }

    override fun classify(status: Int, body: String): UpstreamError =
        AutoglmErrors.classify(status, body)

    // ------------------------------------------------------------------ 凭证刷新

    /**
     * JWT 临期静默刷新；轮换发生时通过 hooks.onAccountUpdated 通知账号池持久化。
     * 返回刷新后的 accessToken（失败返回旧值，不在这里报错——对话层会拿到具体错误）。
     */
    private fun ensureFresh(account: ProviderAccount, parsed: AutoglmAccount): String? {
        val access = parsed.accessToken
        val refresh = parsed.refreshToken
        if (access.isEmpty() || refresh.isEmpty()) return access.ifEmpty { null }
        val exp = jwtClaim(access, "exp").toLongOrNull() ?: 0L
        val now = nowMillis() / 1000L
        if (exp <= 0 || exp - now > AutoglmConstants.REFRESH_MARGIN_SEC) return access

        // 上游自 2026-11 起强制校验 platform + device_id：缺任一个都笼统报 400001。
        // device_id 用登录时保存的那个；老凭证没有时用账号 id 派生一个稳定值
        // （每次刷新都换 device 会被上游判为设备漂移）。
        val deviceId = parsed.deviceId.ifEmpty { derivedDeviceId(account.uid) }
        val payload = JsonObject().apply {
            addProperty("refresh_token", refresh)
            addProperty("source_id", AutoglmConstants.SOURCE_ID)
            addProperty("platform", "web")
            addProperty("device_id", deviceId)
        }
        var res = AutoglmHttp.json(AutoglmConstants.PATH_REFRESH, "POST", access, payload.toString())
        var data = AutoglmErrors.dataOf(res.body)
        if (data.first == null && res.body.contains("400002")) {
            // 400002 = 签名/时钟类：切 agent-refresh 端点重试
            res = AutoglmHttp.json(AutoglmConstants.PATH_AGENT_REFRESH, "POST", access, payload.toString())
            data = AutoglmErrors.dataOf(res.body)
        }
        val refreshed = data.first ?: run {
            hooks.onLog("智谱 AutoGLM 刷新失败：${data.second}（400001=参数校验；400000/410000=登录态失效）")
            return access
        }
        var newAccess = refreshed.str("access_token").ifEmpty { refreshed.str("accessToken") }
        if (newAccess.isEmpty()) {
            hooks.onLog("智谱 AutoGLM 刷新成功但未返回 access_token")
            return access
        }
        if (newAccess.lowercase(Locale.US).startsWith("bearer ")) newAccess = newAccess.substring(7)
        val newRefresh = refreshed.str("refresh_token").ifEmpty { refreshed.str("refreshToken") }
            .ifEmpty { refresh }
        hooks.onAccountUpdated(
            account.copy(secret = parsed.copy(accessToken = newAccess, refreshToken = newRefresh).toSecret()),
        )
        hooks.onLog("智谱 AutoGLM 令牌已刷新")
        return newAccess
    }

    // ------------------------------------------------------------------ 短信登录

    override fun sendSmsCode(phone: String, ccode: String): String {
        val normalized = normalizePhone(phone)
        if (normalized.isEmpty()) throw IllegalStateException("手机号格式不正确（需大陆 1[3-9] 开头 11 位）")
        // 同一手机号复用同一 device_id：发码与登录不一致会被上游拒
        val deviceId = pendingDeviceIds.computeIfAbsent(normalized) { newDeviceId() }
        val payload = JsonObject().apply {
            addProperty("phone", normalized)
            addProperty("source_id", AutoglmConstants.SOURCE_ID)
            addProperty("device_id", deviceId)
        }
        val res = AutoglmHttp.json(AutoglmConstants.PATH_SEND_CODE, "POST", "", payload.toString())
        val (data, error) = AutoglmErrors.dataOf(res.body)
        if (data == null) {
            lastError = error
            throw IllegalStateException(error)
        }
        if (!data.bool("result")) {
            lastError = "上游未确认发送"
            throw IllegalStateException(lastError)
        }
        return "" // AutoGLM 无 msgid 概念，验证码登录只认手机号 + device_id
    }

    override fun loginBySmsCode(
        phone: String,
        code: String,
        msgid: String,
        ccode: String,
    ): ProviderAccount {
        val normalized = normalizePhone(phone)
        if (normalized.isEmpty()) throw IllegalStateException("手机号格式不正确")
        val codeNum = code.trim().toLongOrNull()
            ?: throw IllegalStateException("验证码应为 6 位数字")
        val deviceId = pendingDeviceIds[normalized]
            ?: throw IllegalStateException("请先获取验证码")
        val payload = JsonObject().apply {
            addProperty("phone", normalized)
            addProperty("code", codeNum)
            addProperty("platform", "web")
            addProperty("source_id", AutoglmConstants.SOURCE_ID)
            addProperty("device_id", deviceId)
        }
        val res = AutoglmHttp.json(AutoglmConstants.PATH_LOGIN, "POST", "", payload.toString())
        val (data, error) = AutoglmErrors.dataOf(res.body)
        if (data == null) {
            lastError = error
            throw IllegalStateException(error)
        }
        val access = data.str("access_token")
        if (access.isEmpty()) throw IllegalStateException("登录成功但未返回 access_token")
        val refresh = data.str("refresh_token")
        val userId = jwtClaim(access, "user_id")
        val masked = normalized.substring(0, 3) + "****" + normalized.substring(7)
        pendingDeviceIds.remove(normalized)
        val secret = AutoglmAccount(
            uid = masked,
            accessToken = access,
            refreshToken = refresh,
            deviceId = deviceId,
            userId = userId,
            phone = masked,
        ).toSecret()
        return ProviderAccount(ID, masked, masked, secret)
    }

    // ------------------------------------------------------------------ 额度 / 签到

    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val parsed = parseOrNull(account) ?: return null
        val token = ensureFresh(account, parsed) ?: return null
        val res = AutoglmHttp.json(
            AutoglmConstants.PATH_WALLETS,
            "GET",
            token,
            query = "?biz_app_id=${AutoglmConstants.SOURCE_ID}",
        )
        if (!res.isOk) {
            hooks.onLog("智谱余额查询失败 HTTP ${res.status}")
            return CreditInfo(balance = 0, known = false, detail = "HTTP ${res.status}")
        }
        val data = runCatching {
            JsonParser.parseString(res.body).asJsonObject.getAsJsonObject("data")
        }.getOrNull()
        val total = data?.get("total_balance")?.takeIf { it.isJsonPrimitive }?.asDouble
        if (data == null || total == null) {
            return CreditInfo(balance = 0, known = false, detail = "响应缺少 total_balance")
        }
        return CreditInfo(balance = Math.round(total), unit = CreditUnit.CREDITS)
    }

    override fun performAction(
        account: ProviderAccount,
        action: String,
        payload: JsonObject,
    ): ProviderActionResult = when (action) {
        ACTION_CHECKIN -> checkin(account)
        else -> ProviderActionResult.unsupported(action)
    }

    /** 每日签到（task daily_signin）。 */
    private fun checkin(account: ProviderAccount): ProviderActionResult {
        val parsed = parseOrNull(account) ?: return ProviderActionResult.failure("账号凭证无法解析")
        val token = ensureFresh(account, parsed)
            ?: return ProviderActionResult.failure("登录态已失效，请重新登录")
        val body = JsonObject().apply { addProperty("task_id", "daily_signin") }
        val res = AutoglmHttp.json(AutoglmConstants.PATH_SIGNIN, "POST", token, body.toString())
        if (res.status == 401 || res.status == 403) {
            return ProviderActionResult.failure("登录态已失效，请重新登录")
        }
        if (!res.isOk) {
            hooks.onLog("智谱签到失败 HTTP ${res.status} ${res.body.take(200)}")
            return ProviderActionResult.failure("签到失败（HTTP ${res.status}）")
        }
        val (data, error) = AutoglmErrors.dataOf(res.body)
        if (data == null) return ProviderActionResult.failure(error)
        if (data.bool("already_completed")) return ProviderActionResult.success("今天已签到")
        if (data.bool("success")) {
            val reward = data.get("reward_points")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
            return ProviderActionResult.success(
                if (reward > 0) "签到成功，获得 ${reward.toLong()} 积分" else "签到成功",
            )
        }
        return ProviderActionResult.failure("签到未成功：${res.body.take(160)}")
    }

    // ------------------------------------------------------------------ 工具

    private fun parseOrNull(account: ProviderAccount): AutoglmAccount? =
        runCatching { AutoglmAccount.parse(account.secret) }.getOrNull()

    private fun peekHead(stream: InputStream): Probe {
        val head = ByteArray(64)
        val n = runCatching { stream.read(head) }.getOrDefault(-1)
        if (n <= 0) return Probe(stream, false)
        val text = String(head, 0, n, StandardCharsets.UTF_8)
        val sse = text.startsWith("data:") || text.startsWith(":") || text.contains("event:") ||
            text.startsWith("id:") || text.contains("\ndata:")
        return Probe(java.io.SequenceInputStream(ByteArrayInputStream(head, 0, n), stream), sse)
    }

    private class Probe(val stream: InputStream, val isSse: Boolean)

    /** 64 位 hex device_id（SecureRandom 32 字节）。 */
    private fun newDeviceId(): String {
        val raw = ByteArray(32)
        SecureRandom().nextBytes(raw)
        return raw.joinToString("") { "%02x".format(it) }
    }

    /** 由账号 id 派生 64 位稳定 device_id（老凭证缺 deviceId 时的兑底，跨刷新恒定）。 */
    private fun derivedDeviceId(seed: String): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("autoglm:$seed".toByteArray(Charsets.UTF_8))
        digest.joinToString("") { "%02x".format(it) }
    }.getOrDefault(newDeviceId())

    /** JWT 第 2 段 base64url 解码取字段。 */
    private fun jwtClaim(jwt: String, field: String): String = runCatching {
        val parts = jwt.split(".")
        if (parts.size < 2) return ""
        var seg = parts[1].replace('-', '+').replace('_', '/')
        while (seg.length % 4 != 0) seg += "="
        val payload = JsonParser.parseString(
            String(Base64.getDecoder().decode(seg), StandardCharsets.UTF_8),
        ).asJsonObject
        payload.get(field)?.takeIf { it.isJsonPrimitive }?.asString
            ?: payload.get(field)?.toString().orEmpty()
    }.getOrDefault("")

    /** 手机号归一化：剥 +86/86 前缀，须 1[3-9] 开头 11 位。 */
    private fun normalizePhone(raw: String): String {
        var p = raw.replace(" ", "").replace("-", "")
        if (p.startsWith("+86")) p = p.substring(3)
        else if (p.startsWith("86") && p.length == 13) p = p.substring(2)
        return if (p.matches(Regex("1[3-9]\\d{9}"))) p else ""
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.bool(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

    /** 非流式结果的最小 ChatCall 实现（上游直接回了 JSON）。 */
    private class AggregatedCompat(override val aggregated: String) : ChatCall {
        override val status: Int get() = 200
        override val errorBody: String get() = ""
        override val stream: InputStream? get() = null
        override fun close() = Unit
    }

    companion object {
        const val ID = "autoglm"
    }
}
