// The images the gallery marks as favourites (docs/design.md §6.2). Android only: the lab has no counterpart.
package io.github.bert0ns.cribrum.sources

import android.content.ContentResolver
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The paths MediaStore flags with `IS_FAVORITE`, which Samsung Gallery and Google Photos write.
 * One query that returns the favourites only, so it is cheap to ask again whenever it must be fresh.
 */
suspend fun favoritePaths(resolver: ContentResolver): Set<String> = withContext(Dispatchers.IO) {
    val paths = HashSet<String>()
    val uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    @Suppress("DEPRECATION") // DATA is the only way to the path, and All files access allows reading it
    resolver.query(uri, arrayOf(MediaStore.Images.Media.DATA), "${MediaStore.MediaColumns.IS_FAVORITE} = 1", null, null)?.use { cursor ->
        while (cursor.moveToNext()) cursor.getString(0)?.let(paths::add)
    }
    paths
}
