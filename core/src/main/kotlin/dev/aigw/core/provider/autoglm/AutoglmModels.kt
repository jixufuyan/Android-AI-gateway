package dev.aigw.core.provider.autoglm

import com.google.gson.JsonParser
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * AutoGLM 模型目录与路由。
 *
 * 本地表只是**兑底**：真实路由以 autoclaw-model-config 返回为准（
 * zai_ / zaicoding_ 属于不同权益通道，不同账号可用集合不一样），
 * 不这样做就会出现「内置表里的 routeId 在这台账号上非法」——上游回 400 非法模型。
 */
internal object AutoglmModels {

    /** 内置兑底表：对外名 → routeId。 */
    private val FALLBACK = listOf(
        "glm-5.3" to "zaicoding_glm-5.3",
        "glm-5.3-flash" to "zai_glm-5.3-flash",
    )

    /**
     * 不对外公告的模型：glm-5.3 走付费档，免费档一用就回 403 810000
     * 「免费额度已用完，开通会员继续使用」。直接请求它仍会尝试，
     * 只是失败时记模型冷却、不把账号判成不可用。
     */
    private val HIDDEN = setOf("glm-5.3")

    /** 服务端下发的动态表：小写 id/label → routeId。 */
    private val remote = ConcurrentHashMap<String, String>()

    @Volatile
    private var remoteAt = 0L

    /** 模型路由：返回 (routeId, bodyModel=剥前缀名)。 */
    fun route(requested: String): Pair<String, String> {
        val m = requested.trim().lowercase(Locale.US)
        remote[m]?.let { return it to stripPrefix(it) }
        FALLBACK.firstOrNull { it.first == m }?.let { return it.second to stripPrefix(it.second) }
        if (m.startsWith("zaicoding_") || m.startsWith("zai_")) return m to stripPrefix(m)
        return "zai_auto" to "auto"
    }

    private fun stripPrefix(routeId: String): String = when {
        routeId.startsWith("zaicoding_") -> routeId.removePrefix("zaicoding_")
        routeId.startsWith("zai_") -> routeId.removePrefix("zai_")
        else -> routeId
    }

    /** 对外模型清单：服务端下发的集合优先，未拉到用兑底表；隐藏 HIDDEN。 */
    fun advertised(): List<ProviderModel> {
        val ids = LinkedHashSet<String>()
        for ((id, _) in FALLBACK) if (id !in HIDDEN) ids.add(id)
        for (id in remote.keys) if (id !in HIDDEN) ids.add(id)
        return ids.map { ProviderModel(id = it, name = it, contextWindow = 0) }
    }

    /** 缓存是否仍然新鲜。 */
    fun remoteFresh(nowMillis: Long): Boolean =
        nowMillis - remoteAt < AutoglmConstants.REMOTE_MODEL_TTL_MS && remote.isNotEmpty()

    /** 服务端目录是否为空（true 时目录视图用内置兑底表）。 */
    fun remoteEmpty(): Boolean = remote.isEmpty()

    /**
     * 解析服务端模型目录响应并更新缓存；结构未识别时保留旧表并返回 false。
     *
     * 容忍 data / data.models / models / list 等多种包裹，只接受首个元素带
     * routeId 的数组（避免误匹配 entries/features 等其它数组）。
     */
    fun learnRemote(body: String, nowMillis: Long): Boolean {
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return false
        val arr = findModelArray(root, 0) ?: return false
        val parsed = LinkedHashMap<String, String>()
        for (element in arr) {
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val route = obj.str("routeId").trim()
            if (route.isEmpty()) continue
            val id = obj.str("id").trim()
            val label = obj.str("label").trim()
            if (id.isNotEmpty()) parsed[id.lowercase(Locale.US)] = route
            if (label.isNotEmpty()) parsed[label.lowercase(Locale.US)] = route
        }
        if (parsed.isEmpty()) return false
        remote.clear()
        remote.putAll(parsed)
        remoteAt = nowMillis
        return true
    }

    private fun findModelArray(obj: com.google.gson.JsonObject?, depth: Int): com.google.gson.JsonArray? {
        if (obj == null || depth > 3) return null
        for (key in listOf("models", "modelList", "list", "items", "configs")) {
            val arr = obj.getAsJsonArray(key)
            if (arr != null && arr.size() > 0 &&
                arr[0].takeIf { it.isJsonObject }?.asJsonObject?.str("routeId")?.isNotEmpty() == true
            ) return arr
        }
        val dataArr = obj.getAsJsonArray("data")
        if (dataArr != null && dataArr.size() > 0 &&
            dataArr[0].takeIf { it.isJsonObject }?.asJsonObject?.str("routeId")?.isNotEmpty() == true
        ) return dataArr
        return findModelArray(obj.objOrNull("data"), depth + 1)
    }
}
