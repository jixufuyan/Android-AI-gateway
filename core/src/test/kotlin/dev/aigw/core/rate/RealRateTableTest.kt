package dev.aigw.core.rate

import dev.aigw.core.InMemoryKeyValueStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 拿仓库里真实的那份 `config/model-rates.json` 跑一遍。
 *
 * 单测里固定的 JSON 只能证明「代码逻辑对」，证明不了「仓库里那份表能被现在的代码读进来」。
 * 后者才是用户实际看到的效果，所以这里直接读仓库文件。
 */
class RealRateTableTest {

    private val tableFile = sequenceOf(
        File("../config/model-rates.json"),
        File("config/model-rates.json"),
    ).firstOrNull { it.isFile }

    @Test
    fun `仓库里的倍率表能被完整导入`() {
        val file = tableFile ?: return // 从别的目录跑测试时跳过，不制造假失败
        val book = RateBook(InMemoryKeyValueStore())
        val count = book.importRemote(file.readText())
        assertTrue(count > 0, "仓库里的表一条都没读进来，说明字段格式变了")
        println("[rate] 仓库表导入 $count 条，updated=${book.status().tableUpdated}")
        assertEquals("2026-11-26", book.status().tableUpdated)
    }

    @Test
    fun `每家供应商的条目都能被查到且带来源`() {
        val file = tableFile ?: return
        val book = RateBook(InMemoryKeyValueStore())
        book.importRemote(file.readText())
        var checked = 0
        for ((provider, models) in book.snapshot().entries.groupBy { it.key.substringBefore('/') }) {
            for ((key, entry) in models) {
                val modelId = key.substringAfter('/')
                assertNotNull(book.rate(provider, modelId), "查不到 $key")
                // 表里每条都必须写来源，否则界面没法告诉用户该不该信
                assertTrue(entry.source.isNotEmpty(), "$key 没写来源")
                checked++
            }
        }
        println("[rate] 逐条核对 $checked 条，全部可查且带来源")
        assertTrue(checked > 0)
    }

    @Test
    fun `免费模型解析成免费而不是零倍率`() {
        val file = tableFile ?: return
        val book = RateBook(InMemoryKeyValueStore())
        book.importRemote(file.readText())
        val free = book.snapshot().values.filter { it.isFree }
        println("[rate] 免费模型 ${free.size} 条")
        free.forEach { assertEquals("免费", it.baseLabel()) }
    }
}
