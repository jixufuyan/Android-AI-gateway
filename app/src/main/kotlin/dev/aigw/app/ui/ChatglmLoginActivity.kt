package dev.aigw.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ImageView
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import dev.aigw.app.R
import dev.aigw.app.data.EncryptedKeyValueStore
import com.google.gson.JsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * 智谱清言网页登录（独立 Activity，程序化 UI）。
 *
 * 为什么不用 Compose 页：同一设备上 XML/Activity 的 WebView 能正常渲染
 * agentmore.chatglm.cn，而 Compose AndroidView 容器里一直白屏——本地网关
 * 的同款实现就是这个模式，直接对齐。
 *
 * 流程：打开 agentmore（保留既有 cookie，避免频繁重置设备身份触发短信限频 429）
 * → 每 2 秒轮询双域 cookie + localStorage 兑底的 token
 * → **先在页内验活（user/info）**，通过才返回；
 * 匿名 token 验活不过时留在页内让用户继续登录，绝不自动退出。
 */
class ChatglmLoginActivity : Activity() {

    companion object {
        const val EXTRA_PAYLOAD = "payload"

        const val LOGIN_URL = "https://agentmore.chatglm.cn"
        private const val USER_INFO_URL = "https://agentmore.chatglm.cn/chatglm/user-api/user/info"
        private const val CUR_TS_URL = "https://agentmore.chatglm.cn/chatglm/operation-api/config/cur_ts"
        private val COOKIE_URLS = listOf("https://agentmore.chatglm.cn", "https://chatglm.cn")

        /** 签名盐（与 core 的 ChatglmConstants 同源）。 */
        private const val SIGN_KEY = "8a1317a7468aa3ad86e997d08f3f31cb"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"

        private const val POLL_INTERVAL_MS = 2_000L
        private const val POLL_FIRST_DELAY_MS = 3_000L
    }

    private lateinit var webView: WebView
    private lateinit var stateView: TextView
    private val handler = Handler(Looper.getMainLooper())
    /** 上次验活被拒/已返回的 token：同一 token 不重复提交（匿名 token 会被站点主动下发）。 */
    private var lastRejected = ""
    private var validating = false
    private var finishing = false

    /** 服务器时间偏移（验活签名头用）。 */
    private var tsOffset = 0L
    private var tsSynced = false

    private val poll = object : Runnable {
        override fun run() {
            if (finishing || isFinishing || isDestroyed) return
            detectTokens()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 背景必须与标题栏同为白色：状态栏区域露的是窗口背景，
            // 灰底会出现「状态栏灰 + 标题栏白」的割裂（真实踩过）
            setBackgroundColor(0xFFFFFFFF.toInt())
            // 目标 SDK 35 起 Android 15 强制 edge-to-edge：不主动消费 insets
            // 标题栏会被状态栏盖住
            fitsSystemWindows = true
        }

        // 标题栏对齐百度搭子登录页（Material3 观感）：白底、左箭头、加粗标题、
        // 右侧蓝色文字按钮——系统 Button 的灰底块太难看
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(4), dp(6), dp(16), dp(6))
        }
        // Material arrow_back 矢量图标：TextView 字形「←」的字重/形状与 Compose 侧不一致
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back_dark)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "智谱清言登录"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1F2329.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(TextView(this).apply {
            text = "已完成登录"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            // 跟随主题主色（深色模式自动切换），解析失败回退旧金色
            setTextColor(themeAccent())
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { detectTokens() }
        })
        root.addView(bar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(View(this).apply {
            setBackgroundColor(0xFFECECF0.toInt())
        }, ViewGroup.LayoutParams.MATCH_PARENT, dp(1))

        stateView = TextView(this).apply {
            text = "正在清除旧登录态…"
            setTextColor(0xFF8A8F99.toInt())
            textSize = 12f
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        root.addView(stateView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(View(this).apply {
            setBackgroundColor(0xFFECECF0.toInt())
        }, ViewGroup.LayoutParams.MATCH_PARENT, dp(1))

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            // chatglm-deid 写在 localStorage，DOM storage 必须开
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    detectTokens()
                }

                // 不拦截任何跳转（对齐本地网关）：拦掉 zhipu:// 之类的深链会卡白屏
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return false
                }
            }
        }
        root.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)

        // 不清 cookie/localStorage：每次打开都重置设备身份，风控更容易把
        // 「获取验证码」打到 429（Too Many Requests）。保留既有登录态——
        // 若站点还记得有效 token，轮询会直接自动返回；否则用户手动登录。
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        stateView.text = "登录后自动完成导入；获取验证码报 429 = 上游短信限频，请等几分钟再试"
        webView.loadUrl(LOGIN_URL)

        handler.postDelayed(poll, POLL_FIRST_DELAY_MS)
    }

    /** 双域 cookie + localStorage 兑底；拿到新 token 先页内验活，通过才返回。 */
    private fun detectTokens() {
        if (finishing || validating) return
        val merged = mergedCookies()
        val token = cookieValue(merged, "chatglm_token")
        if (token.isEmpty() || token == lastRejected) return
        validating = true
        stateView.text = "检测到登录态，正在验证…"
        Thread {
            val ok = validateToken(token)
            handler.post {
                validating = false
                if (finishing || isFinishing) return@post
                if (ok) {
                    stateView.text = "登录态有效，正在返回…"
                    fetchDeidAndReturn(token, cookieValue(merged, "chatglm_refresh_token"))
                } else {
                    // 站点对未登录访客也下发匿名 token：验活不过就留在页内让用户继续登录，
                    // 绝不自动退出（真实踩过：一进页就退，提示凭证无效）
                    lastRejected = token
                    stateView.text = "尚未登录或登录态已过期，请在页面里完成登录后自动返回"
                }
            }
        }.start()
    }

    /**
     * 页内验活：GET user/info 拿 member_info.left_score 才算真登录态。
     * 请求需要签名头（X-Timestamp 校准 + 校验位替换 + X-Sign md5），
     * 与 core 的 ChatglmHttp 同一套算法。
     */
    private fun validateToken(token: String): Boolean {
        return try {
            val conn = (URL(USER_INFO_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                for ((k, v) in signedHeaders(token)) setRequestProperty(k, v)
            }
            val code = conn.responseCode
            val body = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                }
            }.getOrNull().orEmpty()
            runCatching { conn.disconnect() }
            if (code !in 200..299) return false
            val result = com.google.gson.JsonParser.parseString(body).asJsonObject
                .getAsJsonObject("result") ?: return false
            val member = result.getAsJsonObject("member_info") ?: return false
            val score = member.get("left_score")?.takeIf { it.isJsonPrimitive }?.asDouble
            score != null && score >= 0
        } catch (_: Exception) {
            false
        }
    }

    /** 签名头（与 core ChatglmHttp.signedHeaders 同款：时间校准 + 校验位替换 + md5）。 */
    private fun signedHeaders(bearer: String): Map<String, String> {
        if (!tsSynced) {
            runCatching {
                val t0 = System.currentTimeMillis()
                val conn = (URL(CUR_TS_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3_000
                    readTimeout = 3_000
                    setRequestProperty("User-Agent", UA)
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                runCatching { conn.disconnect() }
                val server = com.google.gson.JsonParser.parseString(body).asJsonObject
                    .get("result")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
                if (server > 0) {
                    tsOffset = server + (System.currentTimeMillis() - t0) / 2 - System.currentTimeMillis()
                }
            }
            tsSynced = true
        }
        var ts = (System.currentTimeMillis() + tsOffset).toString()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        if (ts.length >= 3) {
            var sum = 0
            for (c in ts) sum += c - '0'
            val check = (sum - (ts[ts.length - 2] - '0')).mod(10)
            ts = ts.substring(0, ts.length - 2) + check + ts.substring(ts.length - 1)
        }
        return buildMap {
            put("Content-Type", "application/json;charset=utf-8")
            put("App-Name", "chatglm")
            put("X-Device-Id", UUID.randomUUID().toString().replace("-", ""))
            put("X-Request-Id", UUID.randomUUID().toString().replace("-", ""))
            put("X-App-Platform", "pc")
            put("X-App-Version", "0.0.1")
            put("X-Timestamp", ts)
            put("X-Nonce", nonce)
            put("X-Sign", md5Hex("$ts-$nonce-$SIGN_KEY"))
            put("User-Agent", UA)
            put("Authorization", "Bearer $bearer")
        }
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** deid 从 localStorage 异步补齐；拿不到留空由网关兑底自动生成。 */
    private fun fetchDeidAndReturn(token: String, refresh: String) {
        webView.evaluateJavascript(
            "try{localStorage.getItem('chatglm-deid')||''}catch(e){''}",
        ) { value ->
            val deid = value.orEmpty().removeSurrounding("\"").takeIf { it != "null" }.orEmpty()
            returnPayload(token, refresh, deid)
        }
    }

    private fun returnPayload(token: String, refresh: String, deviceId: String) {
        if (finishing) return
        finishing = true
        val payload = JsonObject().apply {
            addProperty("token", token)
            addProperty("refreshToken", refresh)
            addProperty("deviceId", deviceId)
        }.toString()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PAYLOAD, payload))
        finish()
    }

    /**
     * 主题主色：读用户自定义的金色渐变起始色（与 AppearanceStore 同键同默认），
     * 未自定义/读到蓝色旧值时回落原版金色 #D4A843。
     */
    private fun themeAccent(): Int {
        val raw = EncryptedKeyValueStore(this).read("settings/appearance.goldStartHex")
            ?.takeIf { it.isNotBlank() && !it.equals("#4A90D9", ignoreCase = true) }
            ?: "#D4A843"
        return runCatching {
            ColorParser.parse(raw)
        }.getOrNull()?.toInt() ?: 0xFFD4A843.toInt()
    }

    /** 仅服务本 Activity 的 #RRGGBB 解析（RRGGBB/#RRGGBB 都认）。 */
    private object ColorParser {
        fun parse(raw: String): Long? {
            val hex = raw.trim().removePrefix("#")
            if (hex.length != 6) return null
            val v = hex.toLongOrNull(16) ?: return null
            return 0xFF000000L or v
        }
    }

    private fun mergedCookies(): String {
        val manager = CookieManager.getInstance()
        manager.flush()
        var merged = ""
        for (url in COOKIE_URLS) {
            val c = manager.getCookie(url).orEmpty()
            if (c.isNotEmpty()) merged = if (merged.isEmpty()) c else "$merged; $c"
        }
        return merged
    }

    private fun cookieValue(raw: String, name: String): String {
        for (part in raw.split(";")) {
            val p = part.trim()
            if (p.startsWith("$name=")) return p.substring(name.length + 1)
        }
        return ""
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else finish()
    }

    override fun onDestroy() {
        finishing = true
        handler.removeCallbacks(poll)
        runCatching {
            webView.stopLoading()
            webView.destroy()
        }
        CookieManager.getInstance().flush()
        super.onDestroy()
    }
}