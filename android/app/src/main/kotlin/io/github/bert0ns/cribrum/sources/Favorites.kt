// The images the gallery marks as favourites (docs/design.md §6.2), and putting the mark back (§9). Android only: the lab has no counterpart.
package io.github.bert0ns.cribrum.sources

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

private val IMAGES: Uri get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

/**
 * The paths MediaStore flags with `IS_FAVORITE`, which Samsung Gallery and Google Photos write.
 * One query that returns the favourites only, so it is cheap to ask again whenever it must be fresh.
 * Blocks: call it off the main thread.
 */
fun queryFavoritePaths(resolver: ContentResolver): Set<String> {
    val paths = HashSet<String>()
    @Suppress("DEPRECATION") // DATA is the only way to the path, and All files access allows reading it
    resolver.query(IMAGES, arrayOf(MediaStore.Images.Media.DATA), "${MediaStore.MediaColumns.IS_FAVORITE} = 1", null, null)?.use { cursor ->
        while (cursor.moveToNext()) cursor.getString(0)?.let(paths::add)
    }
    return paths
}

/** Which of [paths] MediaStore flags as favourites: one query for each 500 paths, not one for the whole library. Blocks. */
fun queryFavoritesAmong(resolver: ContentResolver, paths: Collection<String>): Set<String> {
    val flagged = HashSet<String>()
    for (chunk in paths.chunked(500)) { // SQLite allows 999 arguments in one query
        @Suppress("DEPRECATION") // DATA, as above
        val selection = "${MediaStore.Images.Media.DATA} IN (${chunk.joinToString { "?" }}) AND ${MediaStore.MediaColumns.IS_FAVORITE} = 1"
        resolver.query(IMAGES, arrayOf(MediaStore.Images.Media.DATA), selection, chunk.toTypedArray(), null)?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let(flagged::add)
        }
    }
    return flagged
}

suspend fun favoritePaths(resolver: ContentResolver): Set<String> = withContext(Dispatchers.IO) { queryFavoritePaths(resolver) }

/**
 * Asks the user, with one system dialog, to mark photos as favourites again after a copy or a restore gave them
 * a MediaStore row without the mark (docs/design.md §9). `createFavoriteRequest` needs an Activity to launch it,
 * so the data layer queues an [Ask] and the activity's host launches it when it is in front.
 */
object FavoriteRequests {
    /** [uris] are MediaStore rows without the mark. [onAnswer] gets how many photos stay without it: 0 once granted. */
    class Ask(val uris: List<Uri>, val onAnswer: (lost: Int) -> Unit) {
        /** The system dialog that marks all of [uris] at once. */
        fun intentSender(resolver: ContentResolver): IntentSender = MediaStore.createFavoriteRequest(resolver, uris, true).intentSender
    }

    private val queue = Channel<Ask>(Channel.UNLIMITED)

    /** The requests waiting for the activity, one at a time. */
    val asks: Flow<Ask> = queue.receiveAsFlow()

    private val inFlight = MutableStateFlow<Ask?>(null)

    /** Returns once no request is on screen, so a second one waits for the answer to the first. */
    suspend fun awaitIdle() {
        inFlight.first { it == null }
    }

    /**
     * Queues a request to mark the photos at [paths] that do not have the mark yet, after MediaStore caught up with
     * the files. Nothing is asked, and [onAnswer] gets 0, when every photo has the mark or has no MediaStore row.
     * The mark only exists on a row, so a photo MediaStore does not index (a `.nomedia` folder) has none to lose.
     */
    suspend fun ask(context: Context, paths: Collection<String>, onAnswer: (lost: Int) -> Unit = {}, scan: suspend (Context, Collection<String>) -> Unit = ::scanned) {
        scan(context, paths)
        val uris = unflaggedUris(context.contentResolver, paths)
        if (uris.isEmpty()) onAnswer(0) else queue.send(Ask(uris, onAnswer))
    }

    /** The activity launched [ask]; its answer comes to [answered]. */
    fun launched(ask: Ask) {
        inFlight.value = ask
    }

    /** The user answered the system dialog. A refusal, or a dialog that could not open, leaves every photo of the request without the mark. */
    fun answered(granted: Boolean) {
        val ask = inFlight.getAndUpdate { null } ?: return
        ask.onAnswer(if (granted) 0 else ask.uris.size)
    }
}

/** The MediaStore rows of the images at [paths] that are not favourites. A path with no row is left out. */
internal suspend fun unflaggedUris(resolver: ContentResolver, paths: Collection<String>): List<Uri> = withContext(Dispatchers.IO) {
    paths.chunked(500).flatMap { chunk ->
        // SQLite allows 999 arguments in one query
        val uris = mutableListOf<Uri>()

        @Suppress("DEPRECATION") // DATA, as for the favourites
        val selection = "${MediaStore.Images.Media.DATA} IN (${chunk.joinToString { "?" }})"
        resolver.query(IMAGES, arrayOf(MediaStore.Images.Media._ID, MediaStore.MediaColumns.IS_FAVORITE), selection, chunk.toTypedArray(), null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getInt(1) == 0) uris += ContentUris.withAppendedId(IMAGES, cursor.getLong(0))
        }
        uris
    }
}

/** Waits until MediaStore has read the files at [paths], so their rows are there with their current mark. Gives up after 10 s. */
private suspend fun scanned(context: Context, paths: Collection<String>) {
    if (paths.isEmpty()) return
    withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine { continuation ->
            val left = AtomicInteger(paths.size)
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
                if (left.decrementAndGet() == 0 && continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}
