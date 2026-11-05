package io.github.bert0ns.cribrum.sources

import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.toImageRef
import io.github.bert0ns.cribrum.trash.AppTrash
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Collections

/** A MediaStore with a few image rows, that records the system requests it is asked to build. */
class FakeMediaStore : ContentProvider() {
    class Row(val id: Long, val path: String, val favorite: Boolean)

    class Request(val method: String, val uris: List<Uri>, val value: Boolean?)

    companion object {
        var rows: List<Row> = emptyList()
        val requests = mutableListOf<Request>()
        val queries = mutableListOf<Int>()
        val selections = mutableListOf<String?>()

        fun uri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor {
        queries += args?.size ?: 0
        val wanted = args.orEmpty().toSet()
        selections += selection
        return MatrixCursor(projection!!).apply {
            for (row in rows.filter { it.path in wanted && (it.favorite || selection?.contains("${MediaStore.MediaColumns.IS_FAVORITE} = 1") != true) }) {
                addRow(
                    projection.map<String, Any> {
                        if (it == MediaStore.Images.Media._ID) {
                            row.id
                        } else if (it == MediaStore.MediaColumns.IS_FAVORITE) {
                            (if (row.favorite) 1 else 0)
                        } else {
                            row.path
                        }
                    },
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val clip = extras!!.keySet().firstNotNullOf { extras.get(it) as? ClipData }
        val uris = (0 until clip.itemCount).map { clip.getItemAt(it).uri }
        val values = extras.keySet().firstNotNullOf { extras.get(it) as? ContentValues }
        requests += Request(method, uris, values.getAsInteger(MediaStore.MediaColumns.IS_FAVORITE) == 1)
        val context = context!!
        return Bundle().apply { putParcelable("result", PendingIntent.getActivity(context, 0, Intent("request"), PendingIntent.FLAG_IMMUTABLE)) }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
class FavoriteRequestsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val asked: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    private val noScan: suspend (Context, Collection<String>) -> Unit = { _, _ -> }

    @Before
    fun setUp() {
        FakeMediaStore.rows = emptyList()
        FakeMediaStore.requests.clear()
        FakeMediaStore.queries.clear()
        FakeMediaStore.selections.clear()
        Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    }

    @After
    fun tearDown() = FavoriteRequests.answered(true)

    private fun ask(vararg paths: String) = runBlocking { FavoriteRequests.ask(context, paths.toList(), { asked += it }, noScan) }

    private fun next() = runBlocking { withTimeout(5_000) { FavoriteRequests.next() } }

    @Test
    fun `the photos without the mark go into one request, and those that have it are left out`() {
        FakeMediaStore.rows = listOf(
            FakeMediaStore.Row(1, "/p/a.jpg", favorite = false),
            FakeMediaStore.Row(2, "/p/b.jpg", favorite = true),
            FakeMediaStore.Row(3, "/p/c.jpg", favorite = false),
        )

        ask("/p/a.jpg", "/p/b.jpg", "/p/c.jpg", "/p/not-indexed.jpg")
        val request = next()

        assertEquals(listOf(FakeMediaStore.uri(1), FakeMediaStore.uri(3)), request.uris)
        request.intentSender(context.contentResolver)
        val built = FakeMediaStore.requests.single()
        assertEquals(request.uris, built.uris)
        assertEquals(true, built.value)
        assertTrue(built.method.contains("favorite"))
    }

    @Test
    fun `the favourites among some paths come from a query for those paths and no others`() {
        FakeMediaStore.rows = listOf(
            FakeMediaStore.Row(1, "/p/a.jpg", favorite = true),
            FakeMediaStore.Row(2, "/p/b.jpg", favorite = false),
            FakeMediaStore.Row(3, "/p/c.jpg", favorite = true),
        )

        assertEquals(setOf("/p/a.jpg"), queryFavoritesAmong(context.contentResolver, listOf("/p/a.jpg", "/p/b.jpg")))

        assertEquals(listOf(2), FakeMediaStore.queries)
        assertTrue(FakeMediaStore.selections.single()!!.endsWith("IN (?, ?) AND ${MediaStore.MediaColumns.IS_FAVORITE} = 1"))
        assertEquals(emptySet<String>(), queryFavoritesAmong(context.contentResolver, emptyList()))
    }

    @Test
    fun `a request that is not wanted any more by the time the rows are there is not queued`() {
        FakeMediaStore.rows = listOf(FakeMediaStore.Row(1, "/p/a.jpg", favorite = false))
        var wanted = true

        runBlocking { FavoriteRequests.ask(context, listOf("/p/a.jpg"), { asked += it }, { _, _ -> wanted = false }, { wanted }) }

        assertEquals(null, runBlocking { withTimeoutOrNull(300) { FavoriteRequests.next() } })
        assertEquals(emptyList<Int>(), asked)
    }

    @Test
    fun `nothing is asked when every photo has the mark or has no row, and the caller hears 0 lost`() {
        FakeMediaStore.rows = listOf(FakeMediaStore.Row(2, "/p/b.jpg", favorite = true))

        ask("/p/b.jpg", "/p/not-indexed.jpg")

        assertEquals(listOf(0), asked)
    }

    @Test
    fun `a granted request loses nothing, and a declined one loses every photo of it`() {
        FakeMediaStore.rows = listOf(FakeMediaStore.Row(1, "/p/a.jpg", false), FakeMediaStore.Row(3, "/p/c.jpg", false))
        ask("/p/a.jpg", "/p/c.jpg")
        FavoriteRequests.launched(next())

        FavoriteRequests.answered(granted = false)
        ask("/p/a.jpg", "/p/c.jpg")
        FavoriteRequests.launched(next())
        FavoriteRequests.answered(granted = true)

        assertEquals(listOf(2, 0), asked)
    }

    @Test
    fun `a second request waits for the answer to the first, and a cancelled wait loses nothing`() {
        FakeMediaStore.rows = listOf(FakeMediaStore.Row(1, "/p/a.jpg", false), FakeMediaStore.Row(2, "/p/b.jpg", false))
        ask("/p/a.jpg")
        ask("/p/b.jpg")
        FavoriteRequests.launched(next())

        // The host is cancelled while it waits, as a rotation or Home does.
        assertEquals(null, runBlocking { withTimeoutOrNull(300) { FavoriteRequests.next() } })
        FavoriteRequests.answered(granted = true)

        assertEquals(listOf(FakeMediaStore.uri(2)), next().uris)
    }

    @Test
    fun `a request that never comes back is dropped when the activity is gone, so later ones are not blocked`() {
        FakeMediaStore.rows = listOf(FakeMediaStore.Row(1, "/p/a.jpg", false), FakeMediaStore.Row(2, "/p/b.jpg", false))
        ask("/p/a.jpg")
        ask("/p/b.jpg")
        FavoriteRequests.launched(next())

        FavoriteRequests.abandon()

        assertEquals(listOf(FakeMediaStore.uri(2)), next().uris)
        assertEquals(emptyList<Int>(), asked)
    }

    @Test
    fun `an answer is told once`() {
        FakeMediaStore.rows = listOf(FakeMediaStore.Row(1, "/p/a.jpg", false))
        ask("/p/a.jpg")
        FavoriteRequests.launched(next())

        FavoriteRequests.answered(granted = false)
        FavoriteRequests.answered(granted = false)

        assertEquals(listOf(1), asked)
    }

    @Test
    fun `a long list is looked up in chunks`() {
        val paths = (1..1200).map { "/p/$it.jpg" }
        FakeMediaStore.rows = paths.mapIndexed { i, path -> FakeMediaStore.Row(i + 1L, path, false) }

        runBlocking { FavoriteRequests.ask(context, paths, { asked += it }, noScan) }

        assertEquals(1200, next().uris.size)
        assertEquals(listOf(500, 500, 200), FakeMediaStore.queries)
    }

    /** A trash that trashed [name] while the gallery had it marked, and asks the way the app does when it is restored. */
    private fun trashedFavorite(name: String, scan: suspend (Context, Collection<String>) -> Unit = noScan): Pair<AppTrash, ImageRef> {
        val volume = folder.newFolder("storage")
        val image = File(volume, name).apply { writeText("photo") }.toImageRef()
        val trash = AppTrash({ listOf(volume) }, favorites = { setOf(image.path) }, restoredFavorites = { FavoriteRequests.askAsync(context, it, { lost -> asked += lost }, scan) })
        runBlocking { trash.moveToTrash(listOf(image)) }
        return trash to image
    }

    @Test
    fun `restoring a favourite whose row lost the mark asks for it, in one request`() {
        val (trash, image) = trashedFavorite("a.jpg")
        FakeMediaStore.rows = listOf(FakeMediaStore.Row(7, image.path, favorite = false))

        runBlocking { trash.restore(listOf(image)) }

        assertEquals(listOf(FakeMediaStore.uri(7)), next().uris)
    }

    @Test
    fun `restoring a favourite whose old row kept the mark asks for nothing`() {
        val (trash, image) = trashedFavorite("a.jpg")
        FakeMediaStore.rows = listOf(FakeMediaStore.Row(7, image.path, favorite = true))

        runBlocking { trash.restore(listOf(image)) }

        waitFor { asked.isNotEmpty() }
        assertEquals(listOf(0), asked)
    }

    @Test
    fun `a restore returns at once, while MediaStore's scan is still on, and the request is queued when it ends`() {
        val gate = CompletableDeferred<Unit>()
        val (trash, image) = trashedFavorite("a.jpg") { _, _ -> gate.await() }
        FakeMediaStore.rows = listOf(FakeMediaStore.Row(7, image.path, favorite = false))

        val restored = runBlocking { withTimeout(5_000) { trash.restore(listOf(image)) } }

        assertEquals(listOf(image), restored)
        assertEquals(emptyList<Int>(), asked)
        gate.complete(Unit)
        assertEquals(listOf(FakeMediaStore.uri(7)), next().uris)
    }

    @Test
    fun `one image is marked or unmarked with a request of its own, and its answer says whether the user agreed`() {
        FakeMediaStore.rows = listOf(FakeMediaStore.Row(1, "/p/a.jpg", favorite = false), FakeMediaStore.Row(2, "/p/b.jpg", favorite = true))

        val marked = CoroutineScope(Dispatchers.IO).async { FavoriteRequests.set(context.contentResolver, "/p/a.jpg", true) }
        val mark = next()
        FavoriteRequests.launched(mark)
        FavoriteRequests.answered(granted = true)
        val unmarked = CoroutineScope(Dispatchers.IO).async { FavoriteRequests.set(context.contentResolver, "/p/b.jpg", false) }
        val unmark = next()
        FavoriteRequests.launched(unmark)
        FavoriteRequests.answered(granted = false)

        assertEquals(listOf(FakeMediaStore.uri(1)), mark.uris)
        assertEquals(listOf(FakeMediaStore.uri(2)), unmark.uris)
        mark.intentSender(context.contentResolver)
        unmark.intentSender(context.contentResolver)
        assertEquals(listOf(true, false), FakeMediaStore.requests.map { it.value })
        assertEquals(true, runBlocking { withTimeout(5_000) { marked.await() } })
        assertEquals(false, runBlocking { withTimeout(5_000) { unmarked.await() } })
    }

    @Test
    fun `an image MediaStore does not index cannot be marked, and nothing is queued`() {
        FakeMediaStore.rows = emptyList()

        val granted = runBlocking { FavoriteRequests.set(context.contentResolver, "/p/not-indexed.jpg", true) }

        assertEquals(false, granted)
        assertEquals(null, runBlocking { withTimeoutOrNull(300) { FavoriteRequests.next() } })
    }

    @Test
    fun `a request whose caller is gone is not wanted any more`() {
        FakeMediaStore.rows = listOf(FakeMediaStore.Row(1, "/p/a.jpg", favorite = false))
        val job = CoroutineScope(Dispatchers.IO).async { FavoriteRequests.set(context.contentResolver, "/p/a.jpg", true) }
        val request = next()

        job.cancel()
        runBlocking { job.join() }

        assertEquals(false, request.stillWanted())
    }

    private fun waitFor(condition: () -> Boolean) = runBlocking { withTimeout(5_000) { while (!condition()) yield() } }
}
