package dev.aigw.core.provider.dumate

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 百度通行证（wappass）的 HTTP 底座。
 *
 * 只有一件事需要特别处理：**Set-Cookie 必须全收**。通行证登录成功会一次性下发
 * 十几个 cookie（`BDUSS`/`PTOKEN`/`STOKEN`/`UBI`/`PCSK`/`PASSID`…），
 * 少一个到搭子那边就可能被判成「浏览器指纹不完整」。`HttpURLConnection` 的
 * `getHeaderField("Set-Cookie")` 只回最后一条，所以用 `getHeaderFields()` 全量取。
 */
class DumateLoginHttp(
    private val baseUrl: String = "https://wappass.baidu.com",
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 20_000,
) {

    class Result(
        val status: Int,
        val body: String,
        val setCookies: List<String>,
    ) {
        /**
         * 解析成 JsonObject。
         *
         * 通行证的响应有两种形态：纯 JSON，和「JSONP 包裹 / HTML 里塞 `<script>`」，
         * 这里统一从第一个 `{` 截到最后一个 `}`——只认纯 JSON 会在验证码页拿到
         * 一整个「响应无法解析」。
         */
        fun json(): JsonObject {
            val text = body.trim()
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return JsonObject()
            return runCatching { JsonParser.parseString(text.substring(start, end + 1)).asJsonObject }
                .getOrNull() ?: JsonObject()
        }
    }

    fun post(path: String, form: Map<String, String>): Result =
        request(path, form.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) })

    fun get(path: String): Result = request(path, null)

    /** 发一个请求；[payload] 为 null 时走 GET，否则 POST 表单。 */
    fun request(path: String, payload: String?): Result {
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = if (payload == null) "GET" else "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = payload != null
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent", MOBILE_UA)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("Origin", baseUrl)
            setRequestProperty("Referer", "$baseUrl/passport/?login&tpl=wsk&client=wap")
        }
        return try {
            if (payload != null) conn.outputStream.use { it.write(payload.toByteArray()) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            Result(status, body, collectSetCookies(conn))
        } finally {
            conn.disconnect()
        }
    }

    private fun collectSetCookies(conn: HttpURLConnection): List<String> =
        runCatching {
            conn.headerFields.entries
                .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value.orEmpty() }
        }.getOrDefault(emptyList())

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        /**
         * 通行证会按 UA 分派：桌面串进「要求地理验证」的另一套流程，移动串才是
         * wap 登录页那一套（也就是应用内 WebView 会走的流程）。
         */
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}

/** `data` 层的安全取值（既有 `{"data":{...}}` 也有 `{"data":[{...}]}`）。 */
internal fun JsonObject.dataOrNull(): JsonObject? {
    val direct = objOrNull("data")
    if (direct != null) return direct
    val arr = get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    return arr.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
}

private fun JsonObject.objOrNull(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject
