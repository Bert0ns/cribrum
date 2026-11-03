// The images the gallery marks as favourites (docs/design.md §6.2), and putting the mark back (§9). Android only: the lab has no counterpart.
package io.github.bert0ns.cribrum.sources

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
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
    class Ask(val uris: List<Uri>, val onAnswer: (lost: Int) -> Unit, val stillWanted: () -> Boolean = { true }) {
        /** The system dialog that marks all of [uris] at once. */
        fun intentSender(resolver: ContentResolver): IntentSender = MediaStore.createFavoriteRequest(resolver, uris, true).intentSender
    }

    // A receive that is cancelled just as it returns can lose its element: put that one back.
    private val queue: Channel<Ask> = Channel(Channel.UNLIMITED) { undelivered -> queue.trySend(undelivered) }

    private val inFlight = MutableStateFlow<Ask?>(null)

    /**
     * The next request for the activity to launch, once none is on screen. It waits for the screen to be free
     * before it takes one off the queue, so a cancelled wait (a rotation, Home) leaves every request queued.
     */
    suspend fun next(): Ask {
        inFlight.first { it == null }
        return queue.receive()
    }

    /** The activity is gone for good (not rotating) and no answer will come: later requests must not wait for it. */
    fun abandon() {
        inFlight.value = null
    }

    /**
     * Queues a request to mark the photos at [paths] that do not have the mark yet, after MediaStore caught up with
     * the files. Nothing is asked, and [onAnswer] gets 0, when every photo has the mark or has no MediaStore row.
     * The mark only exists on a row, so a photo MediaStore does not index (a `.nomedia` folder) has none to lose.
     */
    suspend fun ask(
        context: Context,
        paths: Collection<String>,
        onAnswer: (lost: Int) -> Unit = {},
        scan: suspend (Context, Collection<String>) -> Unit = ::scanned,
        stillWanted: () -> Boolean = { true },
    ) {
        scan(context, paths)
        val uris = unflaggedUris(context.contentResolver, paths)
        // Not wanted any more, such as the copies of a batch the user undid meanwhile: they are in the trash now.
        if (!stillWanted()) return
        if (uris.isEmpty()) onAnswer(0) else queue.send(Ask(uris, onAnswer, stillWanted))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [ask] from the app's own scope, so the caller does not wait for MediaStore's scan: a restore returns at once and
     * the request is queued when the rows are there. The wait for the scan is bounded on its own (10 s), and a failure
     * only costs the request, which is logged.
     */
    fun askAsync(
        context: Context,
        paths: Collection<String>,
        onAnswer: (lost: Int) -> Unit = {},
        scan: suspend (Context, Collection<String>) -> Unit = ::scanned,
        stillWanted: () -> Boolean = { true },
    ) {
        val app = context.applicationContext
        scope.launch {
            try {
                ask(app, paths, onAnswer, scan, stillWanted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("favorites", "Could not ask for the favourites", e)
                onAnswer(paths.size)
            }
        }
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
