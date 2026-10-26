package io.github.mangi.eta.data.db

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移链的两条不变量。
 *
 * 这两类缺陷都只在「老用户升级」时才炸，平时开发和全新安装都看不出来：
 * 1. 写了 `MIGRATION` 却没加进 builder（等于没写）；
 * 2. 改了实体、升了版本号，却忘了补那一步迁移。
 *
 * 以前 `exportSchema = false`，也没有导出文件，编译期与测试期都拦不住；
 * 现在迁移链有单一声明源 [EtaDatabase.MIGRATIONS]，schema 快照也落在 `app/schemas/`。
 */
class EtaDatabaseMigrationChainTest {

    @Test
    fun `migrations form a contiguous chain ending at current version`() {
        val steps = EtaDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }.sortedBy { it.first }
        assertTrue("迁移列表为空", steps.isNotEmpty())
        steps.forEach { (start, end) ->
            assertEquals("每一步只能跨一个版本：$start -> $end", start + 1, end)
        }
        assertEquals("同一段迁移写了两遍", steps.size, steps.map { it.first }.toSet().size)
        steps.zipWithNext().forEach { (previous, next) ->
            assertEquals(
                "版本链断在 ${previous.second}：缺少 ${previous.second}->${next.first}",
                previous.second,
                next.first,
            )
        }
        assertEquals("升了版本号却没补迁移", ETA_DATABASE_VERSION, steps.last().second)
    }

    @Test
    fun `current version has an exported schema`() {
        val schema = File("schemas/io.github.mangi.eta.data.db.EtaDatabase/$ETA_DATABASE_VERSION.json")
        assertTrue(
            "缺少导出的 schema：${schema.absolutePath}；exportSchema 被关掉，或改了实体没重新构建",
            schema.isFile,
        )
        val database = JSONObject(schema.readText()).getJSONObject("database")
        assertEquals(ETA_DATABASE_VERSION, database.getInt("version"))
        assertTrue(
            "schema 里的实体表数量异常：${database.getJSONArray("entities").length()}",
            database.getJSONArray("entities").length() >= 10,
        )
    }
}
