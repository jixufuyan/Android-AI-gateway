package dev.aigw.core.provider.chatglm

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.SSE_DONE
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * 智谱清言 SSE → OpenAI SSE 的实时翻译流（本地网关 forwardChatglm produce 同款语义）。
 *
 * 上游帧结构（抓包实证）：
 * - `data: {conversation_id, status, parts[], meta_data, last_error}`；
 * - parts[].content[] 里的 piece：`{"type":"text","text":...}` / `{"type":"think","think":...}`；
 * - meta_data.if_increase_push=true → 增量追加；full=true（无 if_increase_push）→
 *   piece 为全量，替换累计并按前缀差量输出——不实现全量语义正文会成倍重复；
 * - last_error.error_code=10030 = workspace 容器未就绪：出现后流可能还在推字，
 *   只记录；整流结束仍无任何内容时才重连（重新签发一次性签名头，最多 3 次）；
 * - 重签后仍 40014（同会话重复内容）才换新会话再试一次。
 */
internal class ChatglmSseStream(
    private val first: ChatglmStreamResult,
    private val chatId: String,
    private val model: String,
    private val basePayload: JsonObject,
    /** 重开一条流（每次都必须重新签发一次性签名头，payload 可能已被换新会话改写）。 */
    private val reopen: (String) -> ChatglmStreamResult,
    /** 换新会话：返回新 conversation_id，失败返回 null。 */
    private val newConversation: () -> String?,
    /** 首帧回传的 conversation_id 缓存（供后续请求复用）。 */
    private val onConversationId: (String) -> Unit,
    /** 诊断日志（防复读护栏触发记录）。 */
    private val onDebugLog: (String) -> Unit = { _ -> },
) : InputStream() {

    private var reader: BufferedReader = newReader(first.stream)
    private var current: ChatglmStreamResult = first
    private var payload = basePayload.deepCopy()

    private var pending = ByteArray(0)
    private var offset = 0
    private var finished = false

    /** key=logic_id→id→"part" → [text 累计, think 累计]。 */
    private val partState = LinkedHashMap<String, Array<String>>()

    /** 跨帧已下发全文（防全量重推护栏的基准）。 */
    private val globalText = StringBuilder()
    private val globalThink = StringBuilder()

    private var saw10030 = false
    private var emittedAny = false
    private var reconnects = 0
    private var error: String? = null

    private fun newReader(stream: InputStream?): BufferedReader =
        BufferedReader(InputStreamReader(stream ?: InputStream.nullInputStream(), StandardCharsets.UTF_8))

    override fun read(): Int {
        if (!fill()) return -1
        return pending[offset++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val count = minOf(len, pending.size - offset)
        System.arraycopy(pending, offset, b, off, count)
        offset += count
        return count
    }

    override fun close() {
        finished = true
        runCatching { current.close() }
    }

    /** 返回 false 表示流结束。 */
    private fun fill(): Boolean {
        while (offset >= pending.size) {
            if (finished) return false
            val line = reader.readLine()
            if (line == null) {
                onSourceEnd()
                continue
            }
            pending = processLine(line.trim()).toByteArray(StandardCharsets.UTF_8)
            offset = 0
        }
        return true
    }

    /** 当前源流读完：处理 10030 重连或正常收尾。 */
    private fun onSourceEnd() {
        if (saw10030 && !emittedAny && reconnects < ChatglmConstants.RECONNECT_MAX) {
            reconnects++
            runCatching { current.close() }
            // 容器创建是秒级操作，退避放大：2s/4s/6s
            try {
                Thread.sleep(ChatglmConstants.RECONNECT_BACKOFF_BASE_MS * reconnects)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            var retry = reopen(payload.toString())
            if (!retry.isOk) {
                val rbody = retry.errorBody
                // 重签后仍 40014：上游确实按内容去重（同会话重发相同 messages），
                // 此时才换新会话再试一次——保留原会话上下文的代价最小化
                if (retry.status == 400 && rbody.contains("40014")) {
                    val ncid = newConversation()
                    if (!ncid.isNullOrEmpty()) {
                        onConversationId(ncid)
                        payload = basePayload.deepCopy()
                        payload.addProperty("conversation_id", ncid)
                        partState.clear()
                        retry = reopen(payload.toString())
                    }
                }
            }
            if (retry.isOk) {
                current = retry
                reader = newReader(retry.stream)
                saw10030 = false
                return
            }
            error = "workspace 未就绪且重连失败 HTTP ${retry.status} ${retry.errorBody.take(200)}"
            current.close()
        }
        finishStream()
    }

    /** 收尾帧：无字失败发 OpenAI 风格 error 事件，否则发 stop chunk。 */
    private fun finishStream() {
        if (!emittedAny && error == null) {
            error = "上游流结束但无任何内容"
        }
        if (error != null && !emittedAny) {
            pending = ("data: " + JsonObject().apply {
                add("error", JsonObject().apply {
                    addProperty("type", "upstream_error")
                    addProperty("message", error)
                })
            } + "\n\n" + SSE_DONE).toByteArray(StandardCharsets.UTF_8)
        } else {
            pending = ("data: " + stopChunk() + "\n\n" + SSE_DONE)
                .toByteArray(StandardCharsets.UTF_8)
        }
        offset = 0
        finished = true
        runCatching { current.close() }
    }

    /** 处理一行，返回要下发的 SSE 文本（可为空串）。 */
    private fun processLine(line: String): String {
        if (line.isEmpty()) return ""
        if (!line.startsWith("data:")) {
            // 上游错误时回普通 JSON 信封而非 SSE：必须识别为失败，
            // 否则会被当成空流「成功」返回
            if (line.startsWith("{")) {
                val env = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
                val status = env?.get("status")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
                if (status != 0 && status != 200) {
                    error = "上游 $status：" + (env?.str("message").orEmpty())
                    finishStream()
                }
            }
            return ""
        }
        val data = line.substring(5).trim()
        if (data.isEmpty() || data == "[DONE]" || data.equals("HEARTBEAT", true)) return ""
        val frame = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: return ""

        frame.str("conversation_id").trim().takeIf { it.isNotEmpty() }?.let(onConversationId)

        val md = frame.getAsJsonObject("meta_data")
        var lastError = frame.getAsJsonObject("last_error")
        if (lastError == null && md != null) lastError = md.getAsJsonObject("last_error")
        lastError?.get("error_code")?.takeIf { it.isJsonPrimitive }?.asInt?.let { ec ->
            if (ec != 0) {
                if (ec == ChatglmConstants.CODE_WORKSPACE_NOT_READY) {
                    saw10030 = true
                } else {
                    error = when (ec) {
                        ChatglmConstants.CODE_SERVER_BUSY -> "上游 10024：服务器被挤爆了，请稍后重试"
                        ChatglmConstants.CODE_NO_CREDITS -> "上游 -1001：剩余积分不足，可购买积分充值包获得更多积分"
                        else -> "上游错误 $ec" + lastError.str("err_msg")
                            .ifEmpty { lastError.str("message") }
                            .let { if (it.isEmpty()) "" else "：$it" }
                    }
                    finishStream()
                    return ""
                }
            }
        }

        var st = frame.str("status")
        val parts = frame.getAsJsonArray("parts")
        // 帧级 status 常为空，状态藏在 part type 里（intervene/error/finish）
        if (parts != null) {
            for (element in parts) {
                val p = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                when (p.str("type")) {
                    "intervene", "error" -> st = p.str("type")
                    "finish" -> if (st.isEmpty()) st = "finish"
                }
            }
        }
        when (st) {
            "error" -> {
                error = "上游流错误"
                finishStream()
                return ""
            }
            "intervene" -> {
                error = "上游要求人工介入"
                finishStream()
                return ""
            }
        }
        val finish = st == "finish"

        var frameText = ""
        var frameThink = ""
        if (parts != null) {
            for (element in parts) {
                val part = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                if (part.str("type") == "add_input") continue // 用户输入回显，跳过
                val pc = part.getAsJsonArray("content") ?: continue
                // part 级 meta：优先 part 自带，回退帧级
                var pm = part.getAsJsonObject("meta_data") ?: md
                var perr = part.getAsJsonObject("error")
                if (perr == null && pm != null) perr = pm.getAsJsonObject("error")

                var skipPart = false
                var partSaw10030 = false
                for (pieceElement in pc) {
                    val piece = pieceElement.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val pieceMd = piece.getAsJsonObject("meta_data")
                    if (pieceMd != null) {
                        val je = pieceMd.getAsJsonObject("error")
                            ?: pieceMd.getAsJsonObject("last_error")
                        if (je != null && je.get("code")?.takeIf { it.isJsonPrimitive }?.asString == "10030") {
                            partSaw10030 = true
                        }
                    }
                    if (piece.str("type") == "add_input") {
                        skipPart = true
                        break
                    }
                }
                if (partSaw10030 || perr?.get("code")?.takeIf { it.isJsonPrimitive }?.asString == "10030") {
                    saw10030 = true // 记录不拆流，流末统一判定
                }
                if (skipPart) continue

                // 增量/全量语义（对齐参考实现 1.5.9 Ld/w.a 的完整判定链）：
                // if_increase_push 只认 JSON 布尔，part 级优先、【帧级回退】；
                // 仍无再看 full==true（part 级 → 帧级回退）；两者都无 → 增量。
                // 之前只查 part 自带的 meta 一层：part 自带 meta_data（如
                // {"agent":...}）但不含推送标志时不会回退帧级，上游全量重推
                // 被当增量追加 → 整段复读（本地网关 Java 版同款 bug）。
                val inc = run {
                    val iip = pm.jsonFlag("if_increase_push") ?: md.jsonFlag("if_increase_push")
                    iip ?: !((pm.jsonFlag("full") ?: false) || (md.jsonFlag("full") ?: false))
                }
                val showType = pm?.str("show_type").orEmpty()

                var pText = ""
                var pThink = ""
                for (pieceElement in pc) {
                    val piece = pieceElement.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    when (piece.str("type")) {
                        "think" -> {
                            val t = piece.str("think")
                            if (t.isNotEmpty()) pThink += t
                        }
                        "text" -> {
                            // show_type=mc_tool_result2 的工具结果不进正文
                            if (showType == "mc_tool_result2") continue
                            val t = piece.str("text")
                            if (t.isNotEmpty()) {
                                // part type 带 reason/think 的 text 归思维链
                                val type = part.str("type")
                                if (type.contains("reason") || type.contains("think")) {
                                    pThink += t
                                } else {
                                    pText += t
                                }
                            }
                        }
                    }
                }
                if (pText.isEmpty() && pThink.isEmpty()) continue

                // 防复读护栏①：整段文本与已下发全文完全相同（≥30 字）→
                // 上游全量重推（logic_id 换新导致 per-part 差量失效），整段丢弃
                if (pText.length >= 30 && globalText.isNotEmpty() &&
                    globalText.toString().endsWith(pText)
                ) {
                    onDebugLog("智谱防复读①：丢弃整段重推 ${pText.length} 字")
                    continue
                }

                var key = part.str("logic_id")
                if (key.isEmpty()) key = part.str("id")
                if (key.isEmpty()) key = "part"
                val acc = partState.getOrPut(key) { arrayOf("", "") }

                var dText: String
                var dThink: String
                if (inc) {
                    dText = pText
                    dThink = pThink
                    acc[0] += dText
                    acc[1] += dThink
                } else {
                    dText = pText
                    if (dText.startsWith(acc[0])) dText = dText.substring(acc[0].length)
                    dThink = pThink
                    if (dThink.startsWith(acc[1])) dThink = dThink.substring(acc[1].length)
                    acc[0] = pText
                    acc[1] = pThink
                }
                frameText += dText
                frameThink += dThink
            }
        }

        val out = StringBuilder()
        // 每帧至多两条 chunk：思维链增量 + 正文增量
        // delta(...) 已构造完整 chunk（id/object/model/choices），直接拼 SSE 帧头即可
        //
        // 防复读护栏②：上游偶发不带 full/if_increase_push 标志的全量重推帧，
        // 以「已下发全文」为基准兜底：
        // - 新帧以全文开头且更长 → 只发增量尾巴（生长型全量帧）
        // - 新帧与全文完全相同 → 整帧丢弃（等长重复）
        // 只对 ≥30 字的帧生效，避免误伤正常的小增量分片。
        var emitText = frameText
        var emitThink = frameThink
        if (frameText.length >= 30 && globalText.isNotEmpty()) {
            emitText = when {
                frameText == globalText.toString() -> ""
                frameText.startsWith(globalText.toString()) ->
                    frameText.substring(globalText.length)
                else -> frameText
            }
        }
        if (frameThink.length >= 30 && globalThink.isNotEmpty()) {
            emitThink = when {
                frameThink == globalThink.toString() -> ""
                frameThink.startsWith(globalThink.toString()) ->
                    frameThink.substring(globalThink.length)
                else -> frameThink
            }
        }
        globalText.append(emitText)
        globalThink.append(emitThink)
        if (emitThink.isNotEmpty()) {
            out.append("data: ").append(delta(JsonObject().apply {
                addProperty("reasoning_content", emitThink)
            })).append("\n\n")
            emittedAny = true
        }
        if (emitText.isNotEmpty()) {
            out.append("data: ").append(delta(JsonObject().apply {
                addProperty("content", emitText)
            })).append("\n\n")
            emittedAny = true
        }
        if (finish) finishStream()
        return out.toString()
    }

    private fun delta(content: JsonObject): JsonObject = JsonObject().apply {
        addProperty("id", chatId)
        addProperty("object", "chat.completion.chunk")
        addProperty("model", model)
        add("choices", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("index", 0)
                add("delta", content)
                add("finish_reason", com.google.gson.JsonNull.INSTANCE)
            })
        })
    }

    private fun stopChunk(): JsonObject = JsonObject().apply {
        addProperty("id", chatId)
        addProperty("object", "chat.completion.chunk")
        addProperty("model", model)
        add("choices", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("index", 0)
                add("delta", JsonObject())
                addProperty("finish_reason", "stop")
            })
        })
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    /** 只认 JSON 布尔（对齐参考实现 instanceof Boolean 判定，字符串 "true" 不算）。 */
    private fun JsonObject?.jsonFlag(key: String): Boolean? =
        this?.get(key)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean
}
