package dev.aigw.core.provider.dumate

import dev.aigw.core.provider.ProviderHooks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 额度解析：上游字段会变，认不出时要如实说「没拿到」，不能把 0 当成「没额度」。
 *
 * 真机反馈过「账号能加、额度 0/0」——最可能的原因就是字段名与假设不一致，
 * 或者接口回了但 payload 是空的。这里的用例锁死这三种行为：
 * 识别原字段、兼容同义字段、认不出就 known=false。
 */
class DumateCreditParseTest {

    private fun provider(upstream: FakeDumateUpstream) = DumateProvider(
        hooks = ProviderHooks(),
        endpoints = upstream.endpoints,
    )

    private fun account() = dev.aigw.core.provider.ProviderAccount("dumate", "u-1", "测试", DumateAccount(
        uid = "u-1",
        cookie = "BDUSS=abc",
        token = "tk",
        tokenExpiresAtMillis = System.currentTimeMillis() + 3_600_000,
    ).toSecret())

    @Test
    fun `实测字段 totalPoints 与 usedPoints 能算出剩余`() {
        val upstream = FakeDumateUpstream().start()
        try {
            upstream.quotaBody = """{"code":0,"result":{"totalPoints":1000,"usedPoints":250,"isSubscribed":true}}"""
            val info = provider(upstream).creditInfo(account())!!
            assertTrue(info.known, "应识别成功：${info.detail}")
            assertEquals(750, info.balance)
            assertTrue(info.detail.contains("已订阅"))
        } finally {
            upstream.stop()
        }
    }

    @Test
    fun `同义字段 total 与 used 也能识别`() {
        val upstream = FakeDumateUpstream().start()
        try {
            upstream.quotaBody = """{"code":0,"result":{"total":500,"used":120}}"""
            val info = provider(upstream).creditInfo(account())!!
            assertTrue(info.known, "同义字段应识别：${info.detail}")
            assertEquals(380, info.balance)
        } finally {
            upstream.stop()
        }
    }

    @Test
    fun `有 remainPoints 时以它为准`() {
        val upstream = FakeDumateUpstream().start()
        try {
            // 上游 remainPoints 与 total-used 可能不同步（包汇总有延迟），以 remain 为准最接近实际可用
            upstream.quotaBody = """{"code":0,"result":{"totalPoints":1000,"usedPoints":250,"remainPoints":700}}"""
            assertEquals(700, provider(upstream).creditInfo(account())!!.balance)
        } finally {
            upstream.stop()
        }
    }

    @Test
    fun `认不出的字段要报 unknown 而不是 0`() {
        val upstream = FakeDumateUpstream().start()
        try {
            upstream.quotaBody = """{"code":0,"result":{"somethingElse":123}}"""
            val info = provider(upstream).creditInfo(account())!!
            assertFalse(info.known, "认不出应 known=false，否则界面显示「0 额度」骗人")
            assertTrue(info.detail.contains("没有返回可识别"))
        } finally {
            upstream.stop()
        }
    }

    @Test
    fun `接口报错时带上游原话`() {
        val upstream = FakeDumateUpstream().start()
        try {
            upstream.quotaStatus = 401
            upstream.quotaBody = """{"code":10001,"message":"登录已经过期"}"""
            val info = provider(upstream).creditInfo(account())!!
            assertFalse(info.known)
            assertTrue(info.detail.isNotEmpty(), "要带上游原话，别只说失败")
        } finally {
            upstream.stop()
        }
    }

    @Test
    fun `上游给的是带小数的字符串 totalPoints 28700_00`() {
        val upstream = FakeDumateUpstream().start()
        try {
            // 真上游 2026-11-15 实测结构：字符串小数 + 两个包数组
            upstream.quotaBody = """
                {"code":0,"success":true,"result":{
                  "isSubscribed":true,
                  "totalPoints":"28700.00",
                  "usedPoints":"338.42",
                  "modelThrottleInfo":{"throttled":false},
                  "subscription":[{"totalPoints":"25000.00","usedPoints":"100.00","expireDate":1799847249}],
                  "incremental":[{"totalPoints":"3700.00","usedPoints":"238.42","expireDate":1797263999}]
                }}
            """.trimIndent()
            val info = provider(upstream).creditInfo(account())!!
            assertTrue(info.known, "字符串小数必须能解析，否则界面显示 0 额度骗人：${info.detail}")
            // 28700.00 - 338.42 = 28361.58，向下取整成 28361（不夸大可用额度）
            assertEquals(28361, info.balance)
            assertTrue(info.detail.contains("订阅包"), "包明细要出现在结果里：${info.detail}")
            assertTrue(info.detail.contains("增量包"), info.detail)
        } finally {
            upstream.stop()
        }
    }

    @Test
    fun `整数型字符串与小数字面量都能解析`() {
        val upstream = FakeDumateUpstream().start()
        try {
            upstream.quotaBody = """{"code":0,"result":{"totalPoints":"500","usedPoints":120}}"""
            assertEquals(380, provider(upstream).creditInfo(account())!!.balance)
        } finally {
            upstream.stop()
        }
    }
}
