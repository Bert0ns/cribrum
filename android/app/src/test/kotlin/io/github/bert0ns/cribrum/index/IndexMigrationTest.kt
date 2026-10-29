package io.github.bert0ns.cribrum.index

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.cribrum.model.ImageRef
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

private const val NAME = "migration-test.sqlite"

/**
 * Creates a database exactly as schema [version] in app/schemas describes it. Room's own
 * MigrationTestHelper does the same, but reads the schemas from assets, which local tests lack.
 */
private fun createDatabase(context: Context, version: Int): SQLiteDatabase {
    val schema = File("schemas/${IndexDatabase::class.java.name}/$version.json").readText()
    val database = JSONObject(schema).getJSONObject("database")
    val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(NAME).apply { parentFile!!.mkdirs() }, null)
    val entities = database.getJSONArray("entities")
    for (i in 0 until entities.length()) {
        val entity = entities.getJSONObject(i)
        val table = entity.getString("tableName")
        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
        val indices = entity.optJSONArray("indices") ?: continue
        for (j in 0 until indices.length()) {
            db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
    }
    val setup = database.getJSONArray("setupQueries")
    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
    db.version = version
    return db
}

/**
 * Every schema version in app/schemas must open as the current one, with its data. Add a case
 * for each migration. Room refuses to open a database whose schema it does not expect.
 */
@RunWith(RobolectricTestRunner::class)
class IndexMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `version 1 opens with the current schema`() {
        createDatabase(context, 1).use {
            it.execSQL("INSERT INTO images (path, size, mtime_ns) VALUES ('/photos/a.jpg', 100, 1)")
        }

        ImageIndex.open(context, NAME).use {
            assertEquals(listOf(ImageRef("/photos/a.jpg", 100, 1)), runBlocking { it.images() })
            assertEquals(emptyList<String>(), runBlocking { it.excludedFolders() })
        }
    }

    @Test
    fun `version 2 opens with the detectors a kept image had run`() {
        createDatabase(context, 2).use {
            it.execSQL("INSERT INTO images (path, size, mtime_ns, review_state) VALUES ('/photos/a.jpg', 100, 1, 'kept')")
            it.execSQL("INSERT INTO images (path, size, mtime_ns) VALUES ('/photos/b.jpg', 100, 1)")
            it.execSQL("INSERT INTO analyses (path, detector_id, detector_version) VALUES ('/photos/a.jpg', 'blur', 1)")
            it.execSQL("INSERT INTO analyses (path, detector_id, detector_version) VALUES ('/photos/b.jpg', 'blur', 1)")
        }

        ImageIndex.open(context, NAME).use {
            assertEquals(setOf("blur"), runBlocking { it.keptDetectors("/photos/a.jpg") })
            assertEquals(emptySet<String>(), runBlocking { it.keptDetectors("/photos/b.jpg") })
        }
    }

    @Test
    fun `version 3 opens with an empty memory of non-images`() {
        createDatabase(context, 3).use {
            it.execSQL("INSERT INTO images (path, size, mtime_ns) VALUES ('/photos/a.jpg', 100, 1)")
        }

        ImageIndex.open(context, NAME).use {
            assertEquals(listOf(ImageRef("/photos/a.jpg", 100, 1)), runBlocking { it.images() })
            assertEquals(emptyList<ImageRef>(), runBlocking { it.notImages() })
            runBlocking { it.addNotImages(listOf(ImageRef("/photos/notes.txt", 5, 2))) }
            assertEquals(listOf(ImageRef("/photos/notes.txt", 5, 2)), runBlocking { it.notImages() })
        }
    }
}
