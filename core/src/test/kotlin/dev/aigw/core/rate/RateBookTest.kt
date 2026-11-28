package dev.aigw.core.rate

import dev.aigw.core.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 倍率台账与同步器的自检。
 *
 * 重点不在「能解析一份正常的表」，而在**外部输入出错时不许骗人**：
 * 脏数据整条丢弃、失败不清空旧值、现场层不被远程表顶掉。
 * 这些正是「模型页倍率大部分不显示」和「显示了错的数字」两类问题的分界线。
 */
class RateBookTest {

    private fun book(store: InMemoryKeyValueStore = InMemoryKeyValueStore(), now: () -> Long = { 1_000L }) =
        RateBook(store, now)

    // ------------------------------------------------------------------ 解析

    @Test
    fun `合法条目全部字段都能解析`() {
        val json = """
            {"version":3,"updated":"2026-11-26","providers":{
              "trae":{"glm-5.3":{"base":0.78,"source":"官方","note":"官方文档"}},
              "codebuddy":{"hy4std":{"base":0.29,"discounted":0.2,"tag":"会员5折"}}
            }}
        """.trimIndent()
        val b = book()
        assertEquals(2, b.importRemote(json))

        val glm = b.rate("trae", "glm-5.3")
        assertNotNull(glm)
        assertEquals(0.78, glm.base, 1e-9)
        assertNull(glm.discounted)
        assertEquals("官方", glm.source)

        val hy = b.rate("codebuddy", "hy4std")
        assertNotNull(hy)
        assertEquals(0.29, hy.base, 1e-9)
        assertEquals(0.2, hy.discounted!!, 1e-9)
        assertEquals("会员5折", hy.discountTag)
    }

    @Test
    fun `供应商与模型名大小写不敏感`() {
        val b = book()
        b.importRemote("""{"providers":{"Trae":{"GLM-5.3":{"base":0.5}}}}""")
        assertNotNull(b.rate("trae", "glm-5.3"))
        assertNotNull(b.rate("TRAE", "GLM-5.3"))
    }

    @Test
    fun `脏数据整条丢弃而不是猜一个数字`() {
        val b = book()
        // 负数、NaN、超上限、非数字、缺 base：全部不该进台账
        val count = b.importRemote(
            """
            {"providers":{"p":{
              "neg":{"base":-1},
              "nan":{"base":"oops"},
              "huge":{"base":99999},
              "nobase":{"discounted":0.1},
              "ok":{"base":1.5}
            }}}
            """.trimIndent(),
        )
        assertEquals(1, count)
        assertNull(b.rate("p", "neg"))
        assertNull(b.rate("p", "nan"))
        assertNull(b.rate("p", "huge"))
        assertNull(b.rate("p", "nobase"))
        assertNotNull(b.rate("p", "ok"))
    }

    @Test
    fun `结构不对时抛异常由调用方记成失败`() {
        val b = book()
        val thrown = runCatching { b.importRemote("""{"version":3}""") }.isFailure
        assertTrue(thrown, "缺 providers 应该报错，不能静默成功")
        val thrown2 = runCatching { b.importRemote("not json") }.isFailure
        assertTrue(thrown2)
    }

    // ------------------------------------------------------------------ 分层

    @Test
    fun `远程表整体替换旧远程层`() {
        val b = book()
        b.importRemote("""{"providers":{"p":{"a":{"base":1},"b":{"base":2}}}}""")
        assertEquals(2, b.count())
        // 第二份表里没有 b：官方删掉了，不能一直留着旧值
        b.importRemote("""{"providers":{"p":{"a":{"base":3}}}}""")
        assertEquals(1, b.count())
        assertEquals(3.0, b.rate("p", "a")!!.base, 1e-9)
        assertNull(b.rate("p", "b"))
    }

    @Test
    fun `现场数据不会被远程表覆盖`() {
        val b = book()
        b.recordLive("codebuddy", "hy4std", RateEntry(base = 0.29, source = "上游目录"))
        b.importRemote("""{"providers":{"codebuddy":{"hy4std":{"base":9.9}}}}""")
        // 目录当场给的比静态表可靠，同步一次不该把它顶掉
        assertEquals(0.29, b.rate("codebuddy", "hy4std")!!.base, 1e-9)
        assertEquals(RateOrigin.LIVE, b.rate("codebuddy", "hy4std")!!.origin)
    }

    @Test
    fun `零倍率表示免费`() {
        val b = book()
        b.importRemote("""{"providers":{"p":{"free":{"base":0}}}}""")
        val e = b.rate("p", "free")!!
        assertTrue(e.isFree)
        assertEquals("免费", e.baseLabel())
        assertEquals("免费", e.summaryLabel())
    }

    // ------------------------------------------------------------------ 同步

    @Test
    fun `成功同步后记录时间条数与表日期`() {
        val store = InMemoryKeyValueStore()
        var now = 1_000L
        val b = RateBook(store) { now }
        val sync = RateSync(b, fetcher = { """{"updated":"2026-11-26","providers":{"p":{"a":{"base":1}}}}""" }, nowMillis = { now })
        val result = sync.maybeSync()
        assertTrue(result.ok)
        assertEquals(1, result.count)
        val status = b.status()
        assertEquals(1_000L, status.lastSyncAt)
        assertEquals("2026-11-26", status.tableUpdated)
        assertEquals(1, status.entryCount)
    }

    @Test
    fun `失败不清空旧数据只记原因`() {
        val store = InMemoryKeyValueStore()
        var now = 1_000L
        val b = RateBook(store) { now }
        b.importRemote("""{"providers":{"p":{"a":{"base":1}}}}""")
        val sync = RateSync(b, fetcher = { null }, nowMillis = { now })
        val result = sync.maybeSync(force = true)
        assertTrue(!result.ok)
        assertEquals(1, b.count())
        // 旧值还在，界面不该突然变空
        assertNotNull(b.rate("p", "a"))
        assertTrue(b.status().lastError.isNotEmpty())
    }

    @Test
    fun `十二条小时内不重复拉取 失败后三十分钟退避`() {
        val store = InMemoryKeyValueStore()
        var now = 1_000L
        val b = RateBook(store) { now }
        var calls = 0
        val okFetcher = { _: String ->
            calls++
            """{"providers":{"p":{"a":{"base":1}}}}"""
        }
        val sync = RateSync(b, fetcher = okFetcher, nowMillis = { now })

        assertTrue(b.shouldRefresh(now))
        sync.maybeSync()
        assertEquals(1, calls)
        // 成功之后 12 小时内不刷新
        now += 60 * 60 * 1000L
        assertTrue(!b.shouldRefresh(now))
        assertTrue(sync.maybeSync().skipped)
        assertEquals(1, calls)
        // 过了 12 小时才刷新
        now += 12 * 60 * 60 * 1000L
        assertTrue(b.shouldRefresh(now))

        // 失败改成 30 分钟退避
        val failBook = RateBook(InMemoryKeyValueStore()) { now }
        val failSync = RateSync(failBook, fetcher = { null }, nowMillis = { now })
        failSync.maybeSync(force = true)
        now += 10 * 60 * 1000L
        assertTrue(!failBook.shouldRefresh(now), "失败后 10 分钟不该重试")
        now += 25 * 60 * 1000L
        assertTrue(failBook.shouldRefresh(now), "失败 35 分钟后应重试")
    }

    @Test
    fun `线路全挂时把最后一条错误带出来`() {
        val b = book()
        val sync = RateSync(b, fetcher = { "" }, nowMillis = { 5L })
        val result = sync.maybeSync(force = true)
        assertTrue(!result.ok)
        assertTrue(result.error.isNotEmpty())
        // 线路标签要能指出是哪一层哪条线路失败过
        assertTrue(result.source.isNotEmpty())
    }

    // ------------------------------------------------------------------ 持久化

    @Test
    fun `台账与状态能跨实例恢复`() {
        val store = InMemoryKeyValueStore()
        var now = 100L
        val first = RateBook(store) { now }
        first.importRemote("""{"updated":"2026-11-26","providers":{"p":{"a":{"base":0.5}}}}""")
        first.recordLive("live", "m", RateEntry(base = 0.1))

        val second = RateBook(store) { now }
        assertEquals(0.5, second.rate("p", "a")!!.base, 1e-9)
        assertEquals(RateOrigin.REMOTE, second.rate("p", "a")!!.origin)
        assertEquals(0.1, second.rate("live", "m")!!.base, 1e-9)
        assertEquals(RateOrigin.LIVE, second.rate("live", "m")!!.origin)
        assertEquals("2026-11-26", second.status().tableUpdated)
    }

    @Test
    fun `清除台账后状态归零`() {
        val b = book()
        b.importRemote("""{"providers":{"p":{"a":{"base":1}}}}""")
        b.clear()
        assertEquals(0, b.count())
        assertTrue(b.status().neverSynced)
    }

    // ------------------------------------------------------------------ 展示

    @Test
    fun `折扣标签写成原价到现价而不是两个孤立数字`() {
        val e = RateEntry(base = 0.78, discounted = 0.39, discountTag = "会员5折")
        assertEquals("0.78×", e.baseLabel())
        assertEquals("0.39×", e.discountLabel())
        assertEquals("限时 0.39×（会员5折，基准 0.78×）", e.summaryLabel())
    }

    @Test
    fun `倍率格式化去掉多余的零`() {
        assertEquals("0.29", formatMultiplier(0.29))
        assertEquals("1.5", formatMultiplier(1.5))
        assertEquals("2", formatMultiplier(2.0))
        assertEquals("0.03", formatMultiplier(0.03))
    }

    @Test
    fun `转成界面字段时键名与模型页读取的一致`() {
        val extras = RateEntry(
            base = 0.29, discounted = 0.2, discountTag = "会员5折", source = "官方", origin = RateOrigin.REMOTE,
        ).toExtras()
        assertEquals("0.29×", extras["rate"])
        assertEquals("0.2×", extras["rateDiscount"])
        assertEquals("会员5折", extras["rateDiscountTag"])
        assertEquals("官方", extras["rateSource"])
        assertEquals("remote", extras["rateOrigin"])
    }
}
