package io.github.bert0ns.cribrum.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import androidx.work.CoroutineWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.compress.CompressibleImage
import io.github.bert0ns.cribrum.compress.JpegInfo
import io.github.bert0ns.cribrum.compress.JpegStatus
import io.github.bert0ns.cribrum.compress.Losses
import io.github.bert0ns.cribrum.mobileclip.MODEL_ID
import io.github.bert0ns.cribrum.mobileclip.MODEL_VERSION
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.model.toImageRef
import io.github.bert0ns.cribrum.pipeline.DETECTORS
import io.github.bert0ns.cribrum.pipeline.GROUP_DETECTORS
import io.github.bert0ns.cribrum.pipeline.IndexWorker
import io.github.bert0ns.cribrum.pipeline.Indexing
import io.github.bert0ns.cribrum.review.Review
import io.github.bert0ns.cribrum.trash.AppTrash
import io.github.bert0ns.cribrum.ui.ReviewViewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.WeekFields

/** A clock the gallery moves, so photos can go to the trash on different days. */
class MovableClock(var now: Instant = Instant.now()) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}

/** Stands in for the scan: it reports a phase and never ends, so the screens show a scan that runs. */
class RunningScanWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        setProgress(workDataOf(Indexing.STEP to progress.step, Indexing.DONE to progress.done, Indexing.TOTAL to progress.total, Indexing.LEFT_MS to (progress.leftMs ?: -1L)))
        awaitCancellation()
    }

    companion object {
        var progress = io.github.bert0ns.cribrum.pipeline.ScanProgress("detectors", 0, 0)
    }
}

/** Gives the scan's worker class the stand-in above; every other worker is WorkManager's own. */
class GalleryWorkerFactory : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) = if (workerClassName == IndexWorker::class.java.name) RunningScanWorker(appContext, workerParameters) else null
}

/**
 * The pretend phone: a folder tree under [root] with drawn pictures in it, and an index that holds them. Rows without a
 * file stand for the rest of the library: they give the counts and sizes of the home and the map, and never reach a
 * page, because the pictures that do are ranked above them.
 */
class GalleryWorld(val app: CribrumApp, val root: File) {
    val clock = MovableClock()
    val trash = AppTrash({ listOf(root) }, clock)
    val favorites = mutableSetOf<String>()

    private val detectorOf: Map<Category, Pair<String, Int>> =
        (DETECTORS.mapNotNull { d -> d.category?.let { it to (d.id to d.version) } } + GROUP_DETECTORS.mapNotNull { d -> d.category?.let { it to (d.id to d.version) } }).toMap()

    /** Signals waiting for [commit], by detector and path. */
    private val pending = mutableMapOf<String, MutableMap<String, MutableList<Signal>>>()
    private val rows = mutableListOf<ImageRef>()

    fun review() = Review(
        app.index,
        trash,
        thresholds = { app.thresholds.values },
        favorites = { favorites.toSet() },
        firstDayOfWeek = { WeekFields.of(app.resources.configuration.locales[0]).firstDayOfWeek },
    )

    fun model() = ReviewViewModel(app, review())

    fun path(folder: String, name: String) = File(root, "$folder/$name").path

    private val used = mutableSetOf<String>()

    /** The path of a new picture: the generated names collide when two pictures are dated alike, and the index keeps one row for a path. */
    private fun unique(folder: String, name: String): String {
        var candidate = path(folder, name)
        var copy = 1
        while (!used.add(candidate)) candidate = path(folder, "${name.substringBeforeLast('.')}_${copy++}.${name.substringAfterLast('.')}")
        return candidate
    }

    fun signal(category: Category, score: Float, vararg evidence: Pair<String, String>): Signal {
        val (id, version) = detectorOf.getValue(category)
        return Signal(id, version, category, score, evidence.toMap())
    }

    /** Draws [bitmap] into the folder, dated [at], with the size of a real photo, and gives it its [signals]. */
    fun picture(folder: String, name: String, bitmap: Bitmap, bytes: Long, at: LocalDateTime, vararg signals: Signal): ImageRef {
        val file = File(unique(folder, name))
        GalleryPictures.write(file, bitmap, bytes)
        file.setLastModified(at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        return add(file.toImageRef(), signals.toList())
    }

    /** A row with no file behind it. */
    fun row(folder: String, name: String, bytes: Long, at: LocalDateTime, vararg signals: Signal) = add(ImageRef(unique(folder, name), bytes, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() * 1_000_000), signals.toList())

    private fun add(ref: ImageRef, signals: List<Signal>): ImageRef {
        rows += ref
        for (signal in signals) pending.getOrPut(signal.detectorId) { mutableMapOf() }.getOrPut(ref.path) { mutableListOf() } += signal
        return ref
    }

    /** A near duplicate group: the best copy first, then the copies to trash. */
    fun group(id: String, category: Category, best: ImageRef, copies: List<Pair<ImageRef, Float>>, vararg more: Pair<String, String>) {
        fun evidence(role: Role) = arrayOf(GROUP_KEY to id, ROLE_KEY to role.value, *more)
        val signals = pending.getOrPut(detectorOf.getValue(category).first) { mutableMapOf() }
        signals.getOrPut(best.path) { mutableListOf() } += signal(category, 0f, *evidence(Role.BEST))
        for ((copy, score) in copies) signals.getOrPut(copy.path) { mutableListOf() } += signal(category, score, *evidence(Role.INFERIOR))
    }

    /**
     * Writes the rows and signals to the index. With [scanned], every detector has run on every row (a library after a
     * full scan), and the rows have embeddings, as Settings counts them.
     */
    fun commit(scanned: Boolean = true) = runBlocking {
        app.index.recordAll(rows.distinctBy { it.path })
        val every = if (scanned) (DETECTORS.map { it.id } + GROUP_DETECTORS.map { it.id }) else pending.keys
        for (detector in every) {
            val version = (DETECTORS.map { it.id to it.version } + GROUP_DETECTORS.map { it.id to it.version }).toMap().getValue(detector)
            val signals = pending[detector].orEmpty()
            val all = if (scanned) rows.associate { it.path to signals[it.path].orEmpty() } else signals
            app.index.setSignalsOfMany(detector, version, all)
        }
        if (scanned) app.index.setEmbeddings(MODEL_ID, MODEL_VERSION, rows.take(EMBEDDED).associate { it.path to FloatArray(4) })
        rows.clear()
        pending.clear()
    }

    /** Puts [images] in the trash on the days given by [daysAgo], as if the user had trashed them then. */
    fun trashed(images: List<ImageRef>, daysAgo: (Int) -> Long) = runBlocking {
        val today = Instant.now()
        images.forEachIndexed { index, image ->
            clock.now = today.minusSeconds(daysAgo(index) * 86_400)
            trash.moveToTrash(listOf(image))
            app.index.setReviewState(image.path, io.github.bert0ns.cribrum.model.ReviewState.TRASHED)
        }
        clock.now = today
    }

    /** A photo for the Compress page: a drawn picture, a nominal size and resolution as a phone camera gives. */
    fun compressible(name: String, seed: Int, megabytes: Double, width: Int, height: Int, info: JpegInfo = JpegInfo(JpegStatus.ELIGIBLE, quality = 92)): CompressibleImage {
        val file = File(unique(CAMERA, name))
        GalleryPictures.write(file, GalleryPictures.scene(seed, 640, 480), (megabytes * 1_000_000).toLong())
        return CompressibleImage(file.path, (megabytes * 1_000_000).toLong(), width, height, info)
    }

    companion object {
        const val EMBEDDED = 1_500
        val reEdited = Losses(reEdit = true, xmp = true)
    }
}
