package dev.aigw.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * 智谱清言应用内网页登录页（全屏覆盖，登录完成即销毁）。
 *
 * 对齐本地网关 WebViewLoginActivity 的 chatglmFlow 三个关键点：
 * 1. **打开 agentmore.chatglm.cn**（未登录自动跳登录页），不是 chatglm.cn 首页；
 * 2. **每 2 秒轮询凭证**：chatglm 登录是 SPA，登录成功不发生页面跳转，
 *    只靠 onPageFinished 探测永远等不到收口；
 * 3. **双源检测**：cookie 在 agentmore / chatglm 两个域都可能；部分版本令牌
 *    写 localStorage 而不是 cookie——都要兜住。
 *
 * 交付物是 JSON：`{"token":…,"refreshToken":…,"deviceId":…}`，
 * 由 ChatglmProvider.importCredentials 验活（user/info）后建号——
 * 站点对未登录访客也下发匿名 chatglm_token cookie，所以导入必须验活。
 */
@Composable
fun ChatglmLoginScreen(
    onDone: (payload: String) -> Unit,
    onCancel: () -> Unit,
    /** 外部验活失败的原因（非空时展示，页面保持打开继续轮询）。 */
    feedback: String = "",
) {
    var progress by remember { mutableStateOf(0) }
    var hint by remember { mutableStateOf("") }
    // 上次已提交验活的 token：站点对未登录访客也下发匿名 chatglm_token，
    // 不查重会对同一个匿名 token 每 2 秒打一次验活，还把「匿名 token 验活失败」
    // 误当登录完成收口（真实踩过：进页面闪一下就报失败退出）。
    // 真正登录后 token 会变，变化才会再次触发验活。
    var lastSubmitted by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // 导航状态行：白屏排查用——能看到 WebView 到底在加载哪个 URL、进度多少、DOM 是否为空
    var navStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    /** 取 deid 并交付；[refresh]/[deviceId] 可为空，由 provider 兑底。 */
    fun submit(view: WebView, token: String, refresh: String, deviceId: String = "") {
        lastSubmitted = token
        if (deviceId.isNotEmpty()) {
            onDone(buildPayload(token, refresh, deviceId))
            return
        }
        // deid 在 localStorage（异步读）：拿到后拼 JSON 交付，读不到留空由 provider 兑底
        view.evaluateJavascript(
            "try{localStorage.getItem('chatglm-deid')||''}catch(e){''}",
        ) { value ->
            val deid = value.orEmpty().removeSurrounding("\"").takeIf { it != "null" }.orEmpty()
            onDone(buildPayload(token, refresh, deid))
        }
    }

    /** 探一次凭证（cookie 双域 + localStorage 兑底）；拿到新 token 才提交验活。 */
    fun probe() {
        val view = webView ?: return
        val merged = ChatglmLoginWebView.mergedCookies()
        val token = ChatglmLoginWebView.cookieValue(merged, "chatglm_token")
        if (token.isEmpty()) {
            // 兑底：部分版本令牌写 localStorage
            view.evaluateJavascript(
                "(function(){try{var t=localStorage.getItem('chatglm_token')||'';" +
                    "var r=localStorage.getItem('chatglm_refresh_token')||'';" +
                    "var d=localStorage.getItem('chatglm-deid')||'';" +
                    "return t+'|'+r+'|'+d;}catch(e){return '||';}})()",
            ) { value ->
                var raw = value.orEmpty()
                if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
                    raw = raw.substring(1, raw.length - 1)
                        .replace("\\\"", "\"").replace("\\\\", "\\")
                }
                val parts = raw.split("|")
                if (parts.size == 3 && parts[0].isNotEmpty() && parts[0] != lastSubmitted) {
                    submit(view, parts[0], parts[1], parts[2])
                }
            }
            return
        }
        if (token != lastSubmitted) {
            submit(view, token, ChatglmLoginWebView.cookieValue(merged, "chatglm_refresh_token"))
        }
    }

    // SPA 登录无页面跳转：onPageFinished 只在首次加载触发，之后必须轮询
    DisposableEffect(webView) {
        // 对齐本地网关时序：先清 cookie 罐（异步）→ flush → 再 loadUrl。
        // 罐里旧登录态/匿名 token 会让前端渲染分支异常
        val job = scope.launch {
            if (webView != null) {
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    removeAllCookies(null)
                    flush()
                }
                // 捕获页面 JS 错误 + 清站点存储（对齐本地网关）
                webView?.evaluateJavascript(
                    "try{localStorage.clear();sessionStorage.clear();}catch(e){}" +
                        ";window.onerror=function(m,s,l,c){window.__aigwErr=m+' @'+l+':'+c;return false;};",
                    null,
                )
                webView?.clearCache(true)
                webView?.loadUrl(ChatglmLoginWebView.LOGIN_URL)
            }
            while (true) {
                delay(2_000)
                probe()
            }
        }
        onDispose {
            job.cancel()
            webView?.let {
                it.stopLoading()
                it.destroy()
            }
            CookieManager.getInstance().flush()
        }
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onCancel()
    }

    // 背景必须不透明：否则详情页标题会从登录页背后透出来（真实踩过：两个标题叠在一起）
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "智谱清言登录",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    // 兑底：agentmore 渲染不出来时换主站试（同一账号体系，登录后 cookie 同样可用）
                    navStatus = ""
                    webView?.loadUrl(ChatglmLoginWebView.FALLBACK_URL)
                },
            ) {
                Text("换主站加载")
            }
            TextButton(onClick = { probe() }) {
                Text("已完成登录")
            }
        }
        // 外部验活失败的原因优先展示：验活失败时页面保持打开，用户登录后
        // 新 token 会再次触发验活，成功才收口
        val message = feedback.ifEmpty { hint }
        if (message.isNotEmpty()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (navStatus.isNotEmpty()) {
            Text(
                text = navStatus,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        if (progress in 1..99) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val view = ChatglmLoginWebView.createWebView(ctx)
                // loadUrl 不在这里发：等 DisposableEffect 里清完 cookie 罐再加载
                view.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(v, url, favicon)
                        navStatus = "加载中：${url.orEmpty()}"
                    }

                    override fun onPageFinished(v: WebView?, url: String?) {
                        super.onPageFinished(v, url)
                        navStatus = "已加载：${url.orEmpty()}"
                        // 白屏深度诊断：readyState/DOM/文本/HTML 量 + JS 错误一次取齐，
                        // 区分「SPA JS 挂了（DOM 空）」还是「有内容但渲染被挡」
                        v?.evaluateJavascript(
                            "(function(){try{return [document.readyState,document.title," +
                                "document.body?document.body.children.length:-1," +
                                "document.body?document.body.innerText.length:-1," +
                                "document.documentElement?document.documentElement.innerHTML.length:-1," +
                                "(window.__aigwErr||'')].join('|');}catch(e){return 'ERR:'+e;}})()",
                        ) { value ->
                            val raw = value.orEmpty().removeSurrounding("\"")
                            val parts = raw.split("|")
                            navStatus = if (parts.size == 6) {
                                "已加载：${url.orEmpty()}\n" +
                                    "readyState=${parts[0]} 标题=[${parts[1]}] DOM=${parts[2]} 文本=${parts[3]}字 HTML=${parts[4]}字" +
                                    (if (parts[5].isNotEmpty()) "\nJS错误=${parts[5]}" else "")
                            } else {
                                "诊断失败：$raw"
                            }
                        }
                        probe()
                    }

                    // 关键：不拦截任何跳转（对齐本地网关 chatglmFlow）。
                    // 之前把非 http 的（如 zhipu:// 拉起 App 的深链）返回 true 掊死，
                    // 但我们又不去处理它，登录页会被卡在白屏
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        return false
                    }

                    override fun onReceivedError(
                        v: WebView?,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?,
                    ) {
                        super.onReceivedError(v, request, error)
                        // 只报主文档错误：子资源（图片/统计）失败不影响登录
                        if (request?.isForMainFrame == true) {
                            hint = "页面加载失败：${error?.description}（${request.url}）"
                        }
                    }
                }
                // 页面加载进度 + 多窗口：SPA 登录按钮常用 window.open（target=_blank），
                // 不接 onCreateWindow 会静默什么都不发生
                view.settings.setSupportMultipleWindows(true)
                view.webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onProgressChanged(v: WebView?, newProgress: Int) {
                        progress = newProgress
                        if (newProgress in 1..99) navStatus = "加载中 ${newProgress}%…"
                    }

                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message,
                    ): Boolean {
                        // 把弹窗目标 URL 接回同一个 WebView 加载（登录场景不需要真多窗口）：
                        // 塞一个隐藏 WebView 当传输载体，它的 shouldOverrideUrlLoading
                        // 会拿到弹窗目标地址，转给主 WebView 打开
                        val hit = WebView(view.context).apply { setWillNotDraw(true) }
                        resultMsg.obj = hit
                        resultMsg.sendToTarget()
                        hit.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                v: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                webView?.loadUrl(request.url.toString())
                                return true
                            }
                        }
                        return true
                    }
                }
                // 对齐本地网关：清缓存 + 清 localStorage（顺带清掉匿名 token，
                // 避免进页就被验活失败干扰）再加载
                view.clearCache(true)
                webView = view
                view
            },
        )
    }
}

private fun buildPayload(token: String, refresh: String, deviceId: String): String =
    JsonObject().apply {
        addProperty("token", token)
        addProperty("refreshToken", refresh)
        addProperty("deviceId", deviceId)
    }.toString()

/** 登录页 WebView 的构造与 cookie 读取（需要 Android API，所以不在 core 里）。 */
internal object ChatglmLoginWebView {

    /** agentmore 站点：未登录自动跳登录页；token cookie 可能落在它或 chatglm.cn 两个域。 */
    const val LOGIN_URL = "https://agentmore.chatglm.cn"
    /** agentmore 渲染异常时的兑底入口。 */
    const val FALLBACK_URL = "https://chatglm.cn/"
    private val COOKIE_URLS = listOf("https://agentmore.chatglm.cn", "https://chatglm.cn")

    /** Chrome 移动 UA：与 DumateLoginWebView 同款（已验证能在本套 Compose 容器里渲染）。 */
    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(context: Context): WebView = WebView(context).apply {
        // 与 DumateLoginWebView（已验证渲染正常）完全一致的最小配置：
        // 之前加的软件渲染层 / wideViewPort / mixedContent 反而全白——全部回退
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.userAgentString = UA
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    }

    /** 合并两个域的 cookie（与本地网关 detectChatglmTokens 同款双源）。 */
    fun mergedCookies(): String {
        val manager = CookieManager.getInstance()
        manager.flush()
        var merged = ""
        for (url in COOKIE_URLS) {
            val c = manager.getCookie(url).orEmpty()
            if (c.isNotEmpty()) merged = if (merged.isEmpty()) c else "$merged; $c"
        }
        return merged
    }

    /** 从 cookie 串里取单个值；取不到返回空串。 */
    fun cookieValue(raw: String, name: String): String {
        for (part in raw.split(";")) {
            val p = part.trim()
            if (p.startsWith("$name=")) return p.substring(name.length + 1)
        }
        return ""
    }
}
