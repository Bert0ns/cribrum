package io.github.bert0ns.cribrum.index

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The schema the lab writes, from lab/src/cribrum_lab/index.py. */
private fun labSchema(): List<String> {
    val source = File("../../lab/src/cribrum_lab/index.py").readText()
    val sql = source.substringAfter("_SCHEMA = \"\"\"").substringBefore("\"\"\"")
    return sql.lines().joinToString("\n") { it.substringBefore("--") }.split(";").filter { it.isNotBlank() }
}

private interface Query {
    fun rows(sql: String): List<List<String?>>
}

/**
 * Columns, foreign keys and named indexes of every table. Primary key columns count as NOT NULL:
 * Room declares them so, the lab leaves it implicit and never stores a null there.
 */
private fun describe(db: Query): Map<String, List<List<String?>>> {
    val tables = db.rows("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").map { it[0]!! }
        .filter { it !in setOf("room_master_table", "android_metadata", "sqlite_sequence") }
    return tables.associateWith { table ->
        val columns = db.rows("PRAGMA table_info($table)").map { (_, name, type, notNull, default, pk) ->
            listOf("column", name, type, if (pk != "0") "1" else notNull, default, pk)
        }
        // The parent column is left out: the lab names only the parent table, so its primary key.
        val keys = db.rows("PRAGMA foreign_key_list($table)").map { listOf("foreign key", it[2], it[3], it[6]) }
        val indexes = db.rows("PRAGMA index_list($table)").map { it[1]!! }.filterNot { it.startsWith("sqlite_") }
            .map { index -> listOf("index", index) + db.rows("PRAGMA index_info($index)").map { it[2] } }
        columns + keys.sortedBy { it.toString() } + indexes
    }
}

private operator fun <T> List<T>.component6() = this[5]

private fun SQLiteDatabase.query() = object : Query {
    override fun rows(sql: String) = rawQuery(sql, null).use { c ->
        generateSequence { if (c.moveToNext()) (0 until c.columnCount).map(c::getString) else null }.toList()
    }
}

private fun SupportSQLiteDatabase.query() = object : Query {
    override fun rows(sql: String) = query(sql).use { c ->
        generateSequence { if (c.moveToNext()) (0 until c.columnCount).map(c::getString) else null }.toList()
    }
}

@RunWith(RobolectricTestRunner::class)
class IndexSchemaTest {
    @Test
    fun `room creates the lab's schema`() {
        val lab = SQLiteDatabase.create(null).apply { labSchema().forEach(::execSQL) }
        val expected = describe(lab.query()).toMutableMap()
        lab.close()
        // The one difference: Room needs a primary key, and the lab's signals table relies on its rowid.
        expected["signals"] = listOf(listOf("column", "id", "INTEGER", "1", null, "1")) + expected.getValue("signals")

        memoryIndex().use { index ->
            val room = index.database.openHelper.writableDatabase
            val actual = describe(room.query()).toMutableMap()
            actual["signals"] = actual.getValue("signals").sortedBy { it[1] != "id" }

            assertEquals(expected, actual)
        }
    }
}
