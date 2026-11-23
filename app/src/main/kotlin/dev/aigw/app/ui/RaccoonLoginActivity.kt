package dev.aigw.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ImageView
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
import java.net.URLEncoder
import java.util.UUID

/**
 * 小浣熊（商汤）网页登录（独立 Activity，程序化 UI，对齐 [ChatglmLoginActivity]）。
 *
 * 为什么不用系统浏览器：桌面分支的回调是自定义 scheme
 * `office-raccoon://auth/callback?code=…`，外部浏览器接不住；
 * 而带 `redirect` 的网页分支实测授权页会白屏/不回跳。本地网关的
 * WebViewLoginActivity 已验证桌面分支可行，这里对齐：
 * 打开 `/code/authorize?login_source=desktop&appname=办公小浣熊客户端&state=…`，
 * WebView 层拦截回调 scheme 提取 code，合成 `?authorization_code=` 回调链接
 * 交回 [dev.aigw.app.ui.AppViewModel.importCredentials]，走 provider 同一条换取链路。
 */
class RaccoonLoginActivity : Activity() {

    companion object {
        const val EXTRA_PAYLOAD = "payload"

        const val LOGIN_HOST = "https://xiaohuanxiong.com"
        private const val PATH_LOGIN = "/code/authorize"

        /** 桌面客户端分支：授权页用它选回调形态（对齐本地网关 EndpointDiscovery）。 */
        private const val LOGIN_SOURCE = "desktop"
        private const val AUTH_APP_NAME = "办公小浣熊客户端"

        /** 授权完成重定向的自定义 scheme（WebView 层拦截，不会真跳）。 */
        private const val CALLBACK_SCHEME = "office-raccoon"

        /** 拼给 provider.completeWebLogin 的合成回调链接（它认 authorization_code 参数）。 */
        private const val SYNTH_CALLBACK = "http://127.0.0.1:51122/callback"
    }

    private lateinit var webView: WebView
    private lateinit var stateView: TextView
    private var finishing = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 背景必须与标题栏同为白色：状态栏区域露的是窗口背景（同智谱清言页）
            setBackgroundColor(0xFFFFFFFF.toInt())
            // 目标 SDK 35 起 Android 15 强制 edge-to-edge：不主动消费 insets 标题栏会被状态栏盖住
            fitsSystemWindows = true
        }

        // 标题栏：白底、左箭头、加粗标题、右侧文字按钮（与智谱清言登录页同一套观感）
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(4), dp(6), dp(16), dp(6))
        }
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back_dark)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "小浣熊登录"
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
            setOnClickListener { checkCurrentUrl() }
        })
        root.addView(bar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(View(this).apply {
            setBackgroundColor(0xFFECECF0.toInt())
        }, ViewGroup.LayoutParams.MATCH_PARENT, dp(1))

        stateView = TextView(this).apply {
            text = "登录后自动完成导入；若页面停在回调页请点右上角「已完成登录」"
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
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                // 授权完成后页面重定向到 office-raccoon://auth/callback?code=…（WebView 加载不了
                // 自定义 scheme，在这里拦下提取 code）——协议同本地网关桌面客户端分支
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return interceptCallback(request.url.toString())
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    return interceptCallback(url)
                }
            }
        }
        root.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)

        // 不清 cookie：站点已有登录态时授权页会直接重定向回带 code 的回调，秒完成导入。
        // 必须走 login_source=desktop 分支（appname=办公小浣熊客户端，不带 redirect）：
        // 授权页才把 code 回推到 office-raccoon:// 自定义 scheme——这是本地网关验证过的形态
        val state = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        val loginUrl = "$LOGIN_HOST$PATH_LOGIN" +
            "?login_source=" + LOGIN_SOURCE +
            "&appname=" + URLEncoder.encode(AUTH_APP_NAME, "UTF-8") +
            "&state=" + URLEncoder.encode(state, "UTF-8")
        webView.loadUrl(loginUrl)
    }

    /** 拦截 office-raccoon:// 回调，提取 code 后合成回调链接返回应用；返回 true 表示已处理。 */
    private fun interceptCallback(url: String): Boolean {
        if (finishing || !url.startsWith("$CALLBACK_SCHEME://")) return false
        val code = android.net.Uri.parse(url).getQueryParameter("code").orEmpty().trim()
        if (code.isEmpty()) {
            stateView.text = "回调链接里没有授权码，请回到授权页重试"
            return true
        }
        // provider.completeWebLogin 认 authorization_code 参数：拼成它认识的形态走同一条换取链路
        finishWith("$SYNTH_CALLBACK?authorization_code=" + URLEncoder.encode(code, "UTF-8"))
        return true
    }

    /** 右上角「已完成登录」兑底：回调偶尔不走 override 时从当前地址兜一把。 */
    private fun checkCurrentUrl() {
        val url = webView.url.orEmpty()
        if (url.startsWith("$CALLBACK_SCHEME://")) {
            interceptCallback(url)
        } else {
            stateView.text = "尚未拿到授权回调，请在页面里完成授权后自动返回"
        }
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

    private fun finishWith(callbackUrl: String) {
        if (finishing) return
        finishing = true
        stateView.text = "已拿到授权码，正在返回…"
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PAYLOAD, callbackUrl))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else finish()
    }

    override fun onDestroy() {
        finishing = true
        runCatching {
            webView.stopLoading()
            webView.destroy()
        }
        CookieManager.getInstance().flush()
        super.onDestroy()
    }
}
