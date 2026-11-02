package io.github.bert0ns.cribrum.sources

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** A MediaStore that records what it was asked and answers with fixed paths. */
class FakeFavoriteMedia : ContentProvider() {
    companion object {
        var paths: List<String?> = emptyList()
        var projection: List<String>? = null
        var selection: String? = null
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor {
        FakeFavoriteMedia.projection = projection?.toList()
        FakeFavoriteMedia.selection = selection
        return MatrixCursor(arrayOf(MediaStore.Images.Media.DATA)).apply { paths.forEach { addRow(arrayOf(it)) } }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
class FavoritesTest {
    private fun favorites(vararg paths: String?): Set<String> {
        FakeFavoriteMedia.paths = paths.toList()
        Robolectric.setupContentProvider(FakeFavoriteMedia::class.java, MediaStore.AUTHORITY)
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        return runBlocking { favoritePaths(resolver) }
    }

    @Test
    fun `asks MediaStore for the images the gallery marked as favourites and returns their paths`() {
        assertEquals(setOf("/sdcard/DCIM/a.jpg", "/sdcard/DCIM/b.jpg"), favorites("/sdcard/DCIM/a.jpg", "/sdcard/DCIM/b.jpg"))

        assertEquals(listOf(MediaStore.Images.Media.DATA), FakeFavoriteMedia.projection)
        assertEquals("${MediaStore.MediaColumns.IS_FAVORITE} = 1", FakeFavoriteMedia.selection)
    }

    @Test
    fun `a row without a path is skipped, and no favourites is an empty set`() {
        assertEquals(setOf("/sdcard/DCIM/a.jpg"), favorites("/sdcard/DCIM/a.jpg", null))
        assertEquals(emptySet<String>(), favorites())
    }
}
