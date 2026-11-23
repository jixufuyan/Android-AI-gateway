package dev.aigw.core.provider.dumate

import org.junit.jupiter.api.Test

/** 真上游探针：只打印上游真实回包。默认空跑。 */
class LiveLoginProbe {

    @Test
    fun `探针 认证态下通行证各接口的真实回包`() {
        if (System.getProperty("dumate.live", "") != "true") return
        try {
            run()
        } catch (e: Throwable) {
            // 遍历 cause 链：只有最内层才有真实行号
            val sb = StringBuilder("PROBE|ERR|")
            var t: Throwable? = e
            var depth = 0
            while (t != null && depth < 6) {
                sb.append(t.javaClass.simpleName).append('@').append(t.message).append(" <- ")
                t = t.cause
                depth++
            }
            println(sb)
        }
    }

    private fun run() {
        val base = System.getProperty("dumate.pass", "https://wappass.baidu.com")
        val http = DumateLoginHttp(baseUrl = base)
        val device = DumateLogin(http = http).deviceOf("13800138000")
        val common = mapOf(
            "tpl" to "wsk", "client" to "wap", "apiver" to "v3",
            "u" to "https://www.dumate.cn/app", "loginVersion" to "v4", "nalogin" to "0",
            "deviceid" to device.deviceId, "passid" to device.passid, "sign" to device.sign,
        )
        // 先拿 BAIDUID，否则 /wp/api/login 直接回 900001「开启 cookie 之后才能登录」
        http.get("/v3/api/checkapi")
        val steps = listOf(
            "/wp/api/security/getphonestatus" to (common + ("username" to "13800138000")),
            "/wp/api/login" to (common + ("username" to "13800138000") + ("smsvc" to "123456") + ("sms" to "1")),
        )
        for ((path, form) in steps) {
            val res = http.post(path, form)
            val json = res.json()
            val rawMsg = DumateLogin.errMsg(json).orEmpty()
            val msg = runCatching { java.net.URLDecoder.decode(rawMsg, "UTF-8") }.getOrDefault(rawMsg)
            println(
                "PROBE|" + path + "|http=" + res.status + "|cookies=" + res.setCookies.size +
                    "|errno=" + DumateLogin.errNo(json) + "|msg=" + msg + "|",
            )
        }
    }
}
