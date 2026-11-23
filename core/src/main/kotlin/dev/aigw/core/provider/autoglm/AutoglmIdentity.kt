package dev.aigw.core.provider.autoglm

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * AutoGLM 的 system 闸门适配（本地网关 Lu/h + Lx/g 同款）。
 *
 * 上游会拦截声称是其他 coding agent 的提示词（HTTP 406），并对没有 OpenClaw
 * 身份的请求做拦截；这里做两件事：
 * 1. 竞品身份替换（大小写不敏感的子串替换，8 条有序规则）；
 * 2. messages 归一化：首条非 system → 前插 OpenClaw system；首条 system 且
 *    未带身份 → 前置 OpenClaw 文案；无 messages 键 → 原样发送（不注入）。
 */
internal object AutoglmIdentity {

    /** OpenClaw 身份常量：A=预置全文、B=前置用（尾多一空行）、C=身份头。 */
    private const val SYS_A =
        "You are a personal assistant running inside OpenClaw.\n\n## Tooling\n" +
            "Available tools are policy-filtered. Names are case-sensitive; " +
            "call exactly as listed.\n"
    private const val SYS_B =
        "You are a personal assistant running inside OpenClaw.\n\n## Tooling\n" +
            "Available tools are policy-filtered. Names are case-sensitive; " +
            "call exactly as listed.\n\n"
    private const val SYS_C =
        "You are a personal assistant running inside OpenClaw."

    /** 竞品身份替换表（有序，逐条应用）。 */
    private val COMPETITOR_RULES = listOf(
        "You are ZCode, an interactive coding agent" to "You are an interactive coding agent",
        "You are a coding agent running in the Codex CLI tool" to
            "You are a coding agent running in a terminal CLI tool",
        "You are a coding agent running in the Codex CLI" to
            "You are a coding agent running in a terminal CLI",
        "You are running as a coding agent in the Codex CLI" to
            "You are running as a coding agent in a terminal CLI",
        "You are Claude Code" to "You are a coding assistant",
        "You are ZCode" to "You are an interactive coding agent",
        "You are an AI agent powered by DeepSeek Harness" to
            "You are an AI agent powered by a local coding harness",
        "You are Codex" to "You are a coding agent",
    )

    /** 大小写不敏感的 replace-all（逐字符折叠比较，单趟不回溯）。 */
    private fun scrubCompetitors(s: String): String {
        var text = s
        for ((find, rep) in COMPETITOR_RULES) {
            if (text.isEmpty()) break
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                if (i + find.length <= text.length &&
                    text.regionMatches(i, find, 0, find.length, ignoreCase = true)
                ) {
                    out.append(rep)
                    i += find.length
                } else {
                    out.append(text[i])
                    i++
                }
            }
            text = out.toString()
        }
        return text
    }

    /** 对 String 或 parts[].text 两种 content 形态跑竞品替换。 */
    private fun scrubContent(message: JsonObject) {
        val content = message.get("content") ?: return
        if (content.isJsonPrimitive) {
            message.addProperty("content", scrubCompetitors(content.asString))
        } else if (content.isJsonArray) {
            for (part in content.asJsonArray) {
                val obj = part.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val text = obj.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                obj.addProperty("text", scrubCompetitors(text))
            }
        }
    }

    /**
     * messages 归一化（就地修改 [body]）。
     *
     * 无 messages 键时不动请求体：上游对无对话内容的请求有自己的处理，
     * 强行注入反而可能让非对话用途（embedding 之类）报错。
     */
    fun normalize(body: JsonObject) {
        val messages = body.getAsJsonArray("messages") ?: return

        // ① 竞品替换：只作用于 system/developer
        for (element in messages) {
            val message = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val role = message.str("role")
            if (role == "system" || role == "developer") scrubContent(message)
        }

        // ② 首条决策树（只看 messages[0]）
        val first = messages.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        if (first == null) {
            body.add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", SYS_A)
                })
            })
            return
        }
        val role0 = first.str("role")
        if (role0 == "system" || role0 == "developer") {
            val content = first.get("content")
            when {
                content == null || content.isJsonNull -> first.addProperty("content", SYS_A)
                content.isJsonPrimitive -> {
                    val cs = content.asString
                    when {
                        cs.startsWith(SYS_C) -> Unit
                        cs.isEmpty() -> first.addProperty("content", SYS_A)
                        else -> first.addProperty("content", SYS_B + cs)
                    }
                }
                content.isJsonArray -> {
                    val parts = content.asJsonArray
                    val textPart = parts.firstOrNull {
                        it.isJsonObject && it.asJsonObject.get("text")?.isJsonPrimitive == true
                    }?.asJsonObject
                    if (textPart == null) {
                        val out = JsonArray()
                        out.add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", SYS_A)
                        })
                        for (part in parts) out.add(part)
                        first.add("content", out)
                    } else {
                        val t = textPart.str("text")
                        when {
                            t.startsWith(SYS_C) -> Unit
                            t.isEmpty() -> textPart.addProperty("text", SYS_A)
                            else -> textPart.addProperty("text", SYS_B + t)
                        }
                    }
                }
                else -> first.addProperty("content", SYS_A)
            }
        } else {
            // 首条是 user/assistant/tool：前插 OpenClaw system，原消息全部保留
            val out = JsonArray()
            out.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", SYS_A)
            })
            for (element in messages) out.add(element)
            body.add("messages", out)
        }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    /** 供测试使用的纯函数：��请求体 JSON 做完整归一化并返回结果串。 */
    fun normalizeJson(body: String): String {
        val obj = JsonParser.parseString(body).asJsonObject
        normalize(obj)
        return obj.toString()
    }
}
