package io.github.bert0ns.cribrum.ui

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.storage.StorageManager
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.review.Review
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bytes Cribrum takes on the phone (docs/design.md §8). [app] and [cache] are null when Android does not say. */
data class StorageUse(
    val app: Long?,
    val cache: Long?,
    /** The index file without the [embeddings]' data. */
    val index: Long,
    /** An estimate of what the stored vectors take in the index file. */
    val embeddings: Long,
    /** The files in the `.cribrum-trash` folders. */
    val trash: Long,
)

suspend fun measureStorage(app: CribrumApp, review: Review): StorageUse {
    val stats = withContext(Dispatchers.IO) { appStats(app) }
    val embeddings = app.index.embeddingBytes()
    return StorageUse(
        app = stats?.appBytes,
        cache = stats?.cacheBytes,
        index = (app.index.fileBytes() - embeddings).coerceAtLeast(0),
        embeddings = embeddings,
        trash = withContext(Dispatchers.IO) { review.trashed().sumOf { it.file.length() } },
    )
}

/** An app may query its own package without the usage-access permission. Any failure hides the lines. */
private fun appStats(context: Context) = runCatching {
    context.getSystemService(StorageStatsManager::class.java)
        .queryStatsForPackage(StorageManager.UUID_DEFAULT, context.packageName, Process.myUserHandle())
}.getOrNull()
