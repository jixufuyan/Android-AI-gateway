package dev.aigw.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.aigw.core.provider.dumate.DumateCookiePick
import dev.aigw.core.provider.dumate.DumateLoginWeb

/**
 * 应用内网页登录页（全屏覆盖，登录完成即销毁）。
 *
 * 登录走**百度智能云控制台**（`login.bce.baidu.com` → `console.bce.baidu.com`），
 * 不是搭子自己的 wap 登录页——后者只给 `BDUSS`，换不到 token。
 * 详见 [DumateLoginWeb] 的类注释。
 *
 * 四个真机上踩出来的坑（都已处理）：
 * 1. **登录成功不一定停在搭子页**：流程中途会落到 `m.baidu.com` 首页，所以每一页都要探。
 * 2. **判据是两个 cookie**：`BDUSS` + `bce-long-term-sessionid`，缺控制台会话就换不到 token。
 * 3. **缺控制台会话要主动补**：检测到 BDUSS 但没会话时，自己导航一次控制台应用页把会话种下来。
 * 4. **cookie 罐是跨域共享的**：进登录页前先清一次，避免拿用户机器上别的 BDUSS 去换 token。
 */
@Composable
fun DumateLoginScreen(
    onDone: (cookie: String) -> Unit,
    onCancel: () -> Unit,
) {
    var progress by remember { mutableStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    // 探不到/缺项时的提示：不静默退出，把原因写在按钮上方
    var hint by remember { mutableStateOf("") }
    // 最后交给引擎的那份 cookie：probe 命中时带上，供调用方排障
    var lastPicked by remember { mutableStateOf("") }
    // 补救进度：0=还没补，1=已进控制台首页，2=已进搭子应用页（每步只做一次，避免来回跳）
    var settleStage by remember { mutableStateOf(0) }
    val done by rememberUpdatedState(onDone)

    /**
     * 探一次 cookie；四个必需字段齐了就收口。
     *
     * 注意只在**页面加载完成**时调用（onPageFinished），不要在 onPageStarted 里探：
     * 那时 BDUSS 可能已经写进来、但控制台会话还没种下，提前收口就会拿残缺凭证去换 token。
     */
    fun probe(): Boolean {
        if (finished) return true
        val picked = DumateLoginWebView.pickCookie()
        if (picked.isNotEmpty()) {
            finished = true
            lastPicked = picked
            done(picked)
            return true
        }
        return false
    }

    var webView by remember { mutableStateOf<WebView?>(null) }

    /** 判断当前 URL 是否已经是目标页（忽略末尾斜杠与 hash）。 */
    fun samePage(current: String, target: String): Boolean {
        val a = current.substringBefore('#').trimEnd('/')
        val b = target.substringBefore('#').trimEnd('/')
        return a.isNotEmpty() && a == b
    }

    /**
     * 有 BDUSS 但**必需字段没齐**时，逐级把控制台会话种下来（[DumateLoginWeb.SETTLE_URLS]）。
     *
     * 只在这一步才切桌面 UA：登录页要移动 UA 才渲染成 WAP 版，
     * 而 bce-sessionid / bce-login-accountid 是**桌面版控制台**建会话时才下发的。
     *
     * 每一级都等页面 onPageFinished 再进下一级，绝不并发跳转——
     * 连续 loadUrl 会把上一次的重定向链掐断，cookie 就永远种不全（这正是之前失败的原因）。
     */
    fun settleIfNeeded(view: WebView, currentUrl: String) {
        val raw = DumateLoginWebView.rawCookies().joinToString("; ")
        if (!DumateLoginWeb.hasBduss(raw)) return            // 还没登录完，别捣乱
        val missing = DumateCookiePick.missingKeys(raw)
        if (missing.isEmpty()) return                        // 已经齐了，交给 probe 收口
        if (settleStage >= DumateLoginWeb.SETTLE_URLS.size) return

        val target = DumateLoginWeb.SETTLE_URLS[settleStage]
        // 已经在目标页（或它的 hash 路由）上：等它自己跑完，不要重复 loadUrl
        if (samePage(currentUrl, target)) return
        settleStage += 1
        view.post {
            view.settings.userAgentString = DumateLoginWebView.DESKTOP_UA
            view.loadUrl(target)
        }
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onCancel()
    }
    DisposableEffect(Unit) {
        onDispose {
            webView?.let {
                it.stopLoading()
                it.destroy()
            }
            CookieManager.getInstance().flush()
        }
    }

    // 背景必须不透明：否则详情页标题/账号数从背后透出来（真实踩过：两个标题叠在一起重影）
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
                text = "百度搭子登录",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                if (!probe()) {
                    // 报「缺哪几项」而不是一句「登录态不完整」：四项必需字段缺任何一个都换不到 token
                    val raw = DumateLoginWebView.rawCookies().joinToString("; ")
                    val missing = DumateCookiePick.missingKeys(raw)
                    hint = if (DumateLoginWeb.hasBduss(raw)) {
                        "已登录百度账号，但还缺：" + missing.joinToString("、") +
                            "。请等页面进入「百度智能云控制台」并加载完成后再点一次。"
                    } else {
                        "还没检测到百度登录态。请先在页面里完成手机号验证码登录。"
                    }
                }
            }) {
                Text("已完成登录")
            }
        }
        if (hint.isNotEmpty()) {
            SelectionContainer {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        if (progress in 1..99) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val view = DumateLoginWebView.createWebView(ctx)
                view.webViewClient = object : WebViewClient() {
                    // 这里**故意不在 onPageStarted 里探**：那一刻 BDUSS 可能刚写进来、
                    // 控制台会话还没种，提前收口就会拿残缺凭证去换 token（真实踩过）。
                    override fun onPageFinished(v: WebView?, url: String?) {
                        super.onPageFinished(v, url)
                        // 只在这一页彻底加载完之后探：四个必需字段齐了才收口
                        if (probe()) return
                        v?.let { settleIfNeeded(it, url.orEmpty()) }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // http(s) 全在本 WebView 里走完（含控制台内部跳转），
                        // 非 http 的（sms:、intent:、支付）才交回系统
                        return !request.url.toString().startsWith("http")
                    }
                }
                view.loadUrl(DumateLoginWeb.LOGIN_URL)
                webView = view
                view
            },
        )
    }
}

/**
 * 登录页 WebView 的构造与 cookie 读取（需要 Android API，所以不在 core 里）。
 */
internal object DumateLoginWebView {

    /** 进入登录页时清一次 cookie 罐：避免拿用户机器上别的 BDUSS 去换 token。 */
    fun clearJar() {
        val manager = CookieManager.getInstance()
        manager.removeAllCookies(null)
        manager.setAcceptCookie(true)
        manager.flush()
    }

    /** 按域抓齐每个来源的原始 cookie 串（顺序与 [DumateLoginWeb.COOKIE_SOURCES] 一致）。 */
    fun rawCookies(): List<String> {
        val manager = CookieManager.getInstance()
        // Chromium 的 cookie 是异步落盘的，页面刚跳完立刻读可能拿到上一版
        manager.flush()
        return DumateLoginWeb.COOKIE_SOURCES.map { manager.getCookie(it).orEmpty() }
    }

    /** 交给 [DumateCookiePick] 决定发哪份；账号态不完整时返回空串（让界面继续等）。 */
    fun pickCookie(): String = DumateCookiePick.pick(rawCookies())

    /**
     * 把「换不到 token」的原因摊开：每个域抓到几个 cookie、都叫什么（值打码）。
     *
     * 用户的报错只有一句「换不到 token」时，唯一能定位的就是这份清单——
     * 看有没有 `bce-long-term-sessionid` 就能判断是没进控制台，还是进了但会话没种上。
     */
    fun diagnose(pickedCookie: String = ""): String {
        val raws = rawCookies()
        val parts = raws.mapIndexed { index, raw ->
            val names = raw.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
            val label = DumateLoginWeb.COOKIE_SOURCES.getOrNull(index)
                ?.substringAfter("://")?.substringBefore("/") ?: "source$index"
            "$label=${names.size}" + if (names.isEmpty()) "" else "[${names.joinToString(",")}]"
        }
        val pickedNames = pickedCookie.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
        val tail = if (pickedNames.isEmpty()) "交付=无" else "交付=${pickedNames.joinToString(",")}"
        return parts.joinToString("  ") + "  " + tail
    }

    /**
     * 登录阶段用**移动 UA**：`login.bce.baidu.com` 会按 UA 分派，移动串给的是官方 WAP 登录页
     * （标题「百度智能云-WAP登录」，手机号 + 短信验证码两栏），在手机 WebView 里能正常操作。
     * 之前这里错用了桌面 UA，WAP 页被按桌面布局渲染，在手机上挤变形、验证码发不出去 ——
     * 这正是「网页登录一直走不通」的直接原因。
     */
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * 种控制台会话时换成**桌面 UA**：`bce-long-term-sessionid` 只在桌面版控制台
     * （`console.bce.baidu.com`）种下来，移动 UA 进去拿到的是手机版控制台，不带这个 cookie。
     */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    /** 首次加载用移动 UA（登录页）。 */
    const val USER_AGENT = MOBILE_UA

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        // 初值用移动 UA：先让 WAP 登录页正常渲染
        settings.userAgentString = MOBILE_UA
        // 登录页会用到第三方 cookie（通行证下发的 BDUSS 要带到控制台域），缺了登不上
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    }
}
