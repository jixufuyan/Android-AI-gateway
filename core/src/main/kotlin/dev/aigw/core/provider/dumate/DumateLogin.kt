package dev.aigw.core.provider.dumate

import com.google.gson.JsonObject
import dev.aigw.core.gateway.LoginOutcome
import dev.aigw.core.gateway.LoginState
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.util.str
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 百度账号网页登录（手机号 + 短信验证码）的协议实现。
 *
 * 百度通行证（`wappass.baidu.com`）这套接口的要点，全部来自对真上游的实测：
 *
 * 1. **路径是 `/wp/api/…`，不是 `/v3/api/…`**。后者在 nginx 层直接回空包或 302，
 *    看起来像「上游挂了」，实际是路径写错。
 * 2. **先建 cookie 会话再登录**。不带 `BAIDUID` 直接打 `/wp/api/login` 会回
 *    `errno=900001 开启cookie之后才能登录`。所以任何登录动作前先 GET 一次
 *    `/v3/api/checkapi` 把 `BAIDUID` 领到手，并在同一次会话里带上。
 * 3. **成功判据是 `errInfo.no == "0"`**，不是 HTTP 状态码，也不是顶层 `code`：
 *    失败时照样是 200，错误码在 `errInfo.no` 里（`50013` 次数过多、`200010`
 *    验证码失效、`50020/50021` 要图形验证……）。
 * 4. **发验证码本身也可能要过图形验证**（`50020/50021/500002`）。这条路径原生
 *    走不通，只有应用内 WebView 能过——所以默认登录路径是 WebView，本类负责
 *    「WebView 拿到的 cookie 怎么变成账号」以及无验证时的原生直连兜底。
 */
class DumateLogin(
    private val http: DumateLoginHttp = DumateLoginHttp(),
    private val hooks: ProviderHooks = ProviderHooks(),
    /** 搭子端点覆盖：默认走官方域名，测试指向本地假上游。 */
    private val endpoints: DumateEndpoints = DumateEndpoints(),
) {

    /**
     * [accountFromCookie] 成功时留下的账号对象。
     *
     * 那条路径已经完成了「cookie → token → 用户信息」全链路，调用方（引擎）
     * 直接用它落池即可；再调一次 `importCredentials` 会白打一遍同样的接口。
     */
    var lastAccount: ProviderAccount? = null
        private set

    /** 派生出来的设备参数；同一台设备保持稳定，登录与后续接口要一致。 */
    data class Device(
        val deviceId: String,
        val passid: String,
        val sign: String,
    )

    /** 按手机号派生一套稳定的设备参数。 */
    fun deviceOf(phone: String): Device {
        val seed = "aigw-dumate-v1|$phone"
        return Device(
            deviceId = md5(seed),
            // md5 是 32 位 hex：passid 取前 20 位、sign 取后 20 位
            passid = md5("$seed|pass").take(20),
            sign = md5("$seed|sign").takeLast(20),
        )
    }

    // ------------------------------------------------------------------ 短信

    /** 发验证码。成功时把脱敏手机号放在 uid 位置，仅供界面提示。 */
    fun sendSmsCode(phone: String, device: Device = deviceOf(phone)): LoginOutcome {
        val res = runCatching {
            http.get("/v3/api/checkapi")
            http.post("/wp/api/login/sms", buildMap {
                put("username", phone)
                put("countrycode", "")
                putAll(commonParams(device))
            })
        }.getOrElse { return failed(it) }

        val body = res.json()
        return when (val no = errNo(body)) {
            "0" -> LoginOutcome(LoginState.SUCCESS, uid = "短信已发送到 ${maskPhone(phone)}")
            "50020", "50021", "500002" -> LoginOutcome(LoginState.FAILED, error = "需要图形验证，请在应用内网页登录完成")
            else -> LoginOutcome(LoginState.FAILED, error = errMsg(body) ?: "验证码发送失败（errno=$no）")
        }
    }

    /**
     * 用短信验证码登录。
     *
     * `smsvc` 是用户填的码，`sms=1&from=sms` 标记短信登录，`vcodestr/vcodesign`
     * 来自发码回包（无图形验证时为空）。
     */
    fun loginBySmsCode(
        phone: String,
        code: String,
        vcodestr: String = "",
        vcodesign: String = "",
        device: Device = deviceOf(phone),
    ): LoginOutcome {
        val res = runCatching {
            http.post("/wp/api/login", buildMap {
                put("username", phone)
                put("smsvc", code)
                put("sms", "1")
                put("from", "sms")
                put("countrycode", "")
                put("regfrom", "page")
                if (vcodestr.isNotEmpty()) put("vcodestr", vcodestr)
                if (vcodesign.isNotEmpty()) put("vcodesign", vcodesign)
                putAll(commonParams(device))
            })
        }.getOrElse { return failed(it) }
        return finish(res.json(), res, device)
    }

    // ------------------------------------------------------------------ WebView 结果

    /**
     * 把 WebView 里攒到的 cookie 变成账号。
     *
     * 这是默认登录路径的收口：WebView 负责过图形验证与短信，登录成功后
     * `www.dumate.cn` 域下就有 `BDUSS` 等一整套 cookie；这里只做两件事——
     * 校验这套 cookie 真能换到搭子 token，然后落池。
     */
    fun accountFromCookie(cookie: String): LoginOutcome {
        val normalized = DumateProvider.normalizeCookie(cookie)
            ?: return LoginOutcome(LoginState.FAILED, error = "没拿到登录 cookie")
        if (!normalized.contains("BDUSS=")) {
            return LoginOutcome(LoginState.FAILED, error = "登录页还没完成：缺少 BDUSS，请等页面跳回搭子后再试")
        }
        val provider = DumateProvider(hooks = hooks, endpoints = endpoints)
        val token = provider.fetchTokenForLogin(normalized)
            ?: return LoginOutcome(LoginState.FAILED, error = "cookie 换不到搭子 token（登录态可能不完整）")
        val account = provider.accountOf(normalized, token.first, token.second)
        lastAccount = account
        hooks.onLog("搭子登录成功：${account.nickname.ifEmpty { account.uid }}")
        return LoginOutcome(LoginState.SUCCESS, account.uid, account.nickname)
    }

    // ------------------------------------------------------------------ 内部

    private fun commonParams(device: Device): Map<String, String> = linkedMapOf(
        "tpl" to "wsk",
        "client" to "wap",
        "apiver" to "v3",
        // 登录完成后要跳回的站点：填搭子，登录态才会落在搭子的域下
        "u" to DumateConstants.WEB_BASE + "/app",
        "loginVersion" to "v4",
        "nalogin" to "0",
        "deviceid" to device.deviceId,
        "passid" to device.passid,
        "sign" to device.sign,
    )

    /** 登录成功后的统一收尾：Set-Cookie + `data.xml` 双收，拼好整包交出去。 */
    private fun finish(body: JsonObject, res: DumateLoginHttp.Result, device: Device): LoginOutcome {
        val no = errNo(body)
        if (no != "0") {
            return LoginOutcome(LoginState.FAILED, error = errMsg(body) ?: "登录失败（errno=$no）")
        }
        val jar = LinkedHashMap<String, String>()
        // Set-Cookie 顺序是先通用设备 cookie、后账号 cookie，后者覆盖同名项
        res.setCookies.forEach { line ->
            val pair = line.substringBefore(';')
            val name = pair.substringBefore('=', "")
            val value = pair.substringAfter('=', "")
            if (name.isNotEmpty() && value.isNotEmpty()) jar[name] = value
        }
        jar.putAll(parseXmlCookies(body.dataOrNull()?.str("xml").orEmpty()))
        if (jar["PASSID"].isNullOrEmpty()) jar["PASSID"] = device.passid

        if (jar["BDUSS"].isNullOrEmpty()) {
            return LoginOutcome(LoginState.FAILED, error = "登录成功响应里没有 BDUSS（可能需要二次验证）")
        }
        return accountFromCookie(jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }

    /** 从客户端登录 XML 里抽 cookie（`<bduss>` / `<stoken>` / `<ptoken>` / `<ubi>`）。 */
    private fun parseXmlCookies(xml: String): Map<String, String> {
        if (xml.isEmpty()) return emptyMap()
        val mapping = linkedMapOf("bduss" to "BDUSS", "stoken" to "STOKEN", "ptoken" to "PTOKEN", "ubi" to "UBI")
        return buildMap {
            for ((tag, cookieName) in mapping) {
                val value = Regex("<$tag>([^<]*)</$tag>").find(xml)?.groupValues?.getOrNull(1).orEmpty()
                if (value.isNotEmpty()) put(cookieName, unescapeXml(value))
            }
        }
    }

    private fun unescapeXml(value: String): String = value
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun failed(e: Throwable): LoginOutcome {
        hooks.onLog("百度登录请求失败：${e.message}")
        val detail = "$e".take(200)
        return LoginOutcome(LoginState.FAILED, error = "网络请求失败：$detail")
    }

    companion object {
        /** 上游回的 `errInfo.no`；`"0"` 才是成功。 */
        fun errNo(body: JsonObject): String =
            body.get("errInfo")?.takeIf { it.isJsonObject }?.asJsonObject?.str("no").orEmpty()

        fun errMsg(body: JsonObject): String? {
            val inner = body.get("errInfo")?.takeIf { it.isJsonObject }?.asJsonObject?.str("msg").orEmpty()
            val decoded = runCatching { java.net.URLDecoder.decode(inner, "UTF-8") }.getOrDefault(inner)
            if (decoded.isNotEmpty()) return decoded
            val outer = body.str("msg")
            return outer.ifEmpty { null }
        }

        fun maskPhone(phone: String): String =
            if (phone.length >= 7) phone.substring(0, 3) + "****" + phone.substring(phone.length - 4) else phone

        fun md5(value: String): String =
            MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

        fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
