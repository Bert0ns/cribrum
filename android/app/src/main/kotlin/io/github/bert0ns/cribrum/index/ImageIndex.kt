// Persistent image index, so later scans only process new or changed files. Mirrors
// lab/src/cribrum_lab/index.py method by method.
package io.github.bert0ns.cribrum.index

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import io.github.bert0ns.cribrum.compress.CompressibleImage
import io.github.bert0ns.cribrum.compress.JPEG_RULES_VERSION
import io.github.bert0ns.cribrum.compress.JpegInfo
import io.github.bert0ns.cribrum.compress.JpegStatus
import io.github.bert0ns.cribrum.compress.Losses
import io.github.bert0ns.cribrum.compress.Marker
import io.github.bert0ns.cribrum.folders.isExcluded
import io.github.bert0ns.cribrum.metadata.ImageMetadata
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.IndexedImage
import io.github.bert0ns.cribrum.model.Kind
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.model.SignalIdentity
import io.github.bert0ns.cribrum.sources.NotImages
import io.github.bert0ns.cribrum.tracing.traceSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/** Methods taking a path throw [NoSuchElementException] for an image that was never recorded. */
class ImageIndex(private val db: IndexDatabase) :
    AutoCloseable,
    NotImages {
    /** For tests that look at the schema itself. */
    internal val database: IndexDatabase get() = db

    private val dao = db.dao()

    private val writes = AtomicLong()

    /**
     * Bumped after every write, so a reader that takes it before a read can tell whether what it read is still current.
     * It sees every write because the app opens one index per process (CribrumApp.index), and the scan worker writes
     * through that one too.
     */
    val version: Long get() = writes.get()

    /**
     * Adds or updates an image. Returns true if it is new or changed.
     *
     * A changed image (different size or mtime) loses its metadata, hashes, embeddings,
     * unreadable mark and signals, which must be computed again. It keeps its review state:
     * the file may only have been touched, and a decision the user made must not be lost. A real
     * edit shows up as new signals, which the ranker treats as such. A trashed row is the exception:
     * a file on disk at its path is not the one in the trash, so the row starts unreviewed.
     * An unchanged image keeps the version: a scan records every file, and would otherwise drop the review's ranking.
     */
    suspend fun record(image: ImageRef): Boolean = recordAll(listOf(image)) > 0

    /**
     * [record] for many images in one transaction, which is much cheaper than one each. Returns how many are new or
     * changed. If it throws, none of them is recorded and the version stays.
     */
    suspend fun recordAll(images: List<ImageRef>): Int = traceSuspend("Index.recordAll") {
        db.withTransaction {
            // One lookup for many paths, in the same transaction: one for each path took most of the time.
            val stored = HashMap<String, StoredRef>()
            images.map { it.path }.distinct().chunked(SQL_VARIABLES).forEach { paths -> dao.refs(paths).associateByTo(stored) { it.path } }
            images.count { recordOne(it, stored) }
        }
    }.also { changed -> if (changed > 0) writes.incrementAndGet() }

    /** Records [image] against its row in [stored], and keeps [stored] as the row now is, for a path the list holds twice. */
    private suspend fun recordOne(image: ImageRef, stored: MutableMap<String, StoredRef>): Boolean {
        val row = stored[image.path]
        when {
            row == null -> dao.insertImage(ImageRow(image.path, image.size, image.mtimeNs))
            row.size == image.size && row.mtimeNs == image.mtimeNs -> return false
            else -> {
                dao.resetImage(image.path, image.size, image.mtimeNs)
                dao.deleteAnalyses(image.path)
                dao.deleteEmbeddings(image.path)
                dao.deleteJpeg(image.path)
            }
        }
        stored[image.path] = StoredRef(image.path, image.size, image.mtimeNs)
        return true
    }

    suspend fun images(): List<ImageRef> = dao.images().map { ImageRef(it.path, it.size, it.mtimeNs) }

    /** Removes images from the index, with everything stored about them. Files are untouched. */
    suspend fun forget(paths: Collection<String>) = delete {
        paths.chunked(SQL_VARIABLES).sumOf { dao.forget(it) }
    }

    /**
     * [forget] for the trash's purge: only rows still TRASHED go. A new file at the path of a purged image has a row
     * of its own, with a state the user chose, and it stays.
     */
    suspend fun forgetTrashed(paths: Collection<String>) = delete {
        paths.chunked(SQL_VARIABLES).sumOf { dao.forgetTrashed(it) }
    }

    /** Images whose metadata was never read, leaving out the unreadable ones. */
    suspend fun withoutMetadata(): List<ImageRef> = dao.withoutMetadata().map { ImageRef(it.path, it.size, it.mtimeNs) }

    /** Marks an image whose header can't be decoded, so it isn't retried until it changes. */
    suspend fun setUnreadable(path: String) = write { require(dao.setUnreadable(path), path) }

    suspend fun unreadable(): List<String> = dao.unreadable()

    suspend fun setMetadata(path: String, metadata: ImageMetadata) = write {
        require(
            dao.setMetadata(
                path,
                metadata.width,
                metadata.height,
                metadata.mime,
                metadata.cameraMake,
                metadata.cameraModel,
                metadata.capturedAt?.format(ISO_SECONDS),
            ),
            path,
        )
    }

    suspend fun metadata(path: String): ImageMetadata? = dao.metadata(path)?.let(::metadata)

    /** The metadata of every image that has it, by path: one query, where a group detector would make one per image. */
    suspend fun allMetadata(): Map<String, ImageMetadata> = dao.allMetadata().associate { it.path to metadata(it.metadata) }

    suspend fun setContentHash(path: String, digest: String) = write { require(dao.setContentHash(path, digest), path) }

    suspend fun contentHash(path: String): String? {
        requireRecorded(path)
        return dao.contentHash(path)
    }

    /** Stored as hex text: SQLite integers are signed and a hash may use all 64 bits. */
    suspend fun setPerceptualHash(path: String, hash: ULong) = write { require(dao.setPerceptualHash(path, hash.toString(16).padStart(16, '0')), path) }

    suspend fun perceptualHash(path: String): ULong? {
        requireRecorded(path)
        return dao.perceptualHash(path)?.toULong(16)
    }

    suspend fun hashedPaths(): Set<String> = dao.hashedPaths().toSet()

    /** The perceptual hash of every image that has one, by path, as [allMetadata] does for metadata. */
    suspend fun perceptualHashes(): Map<String, ULong> = dao.perceptualHashes().associate { it.path to it.perceptualHash.toULong(16) }

    /** The review state of every image by path, for a pass that must tell whether a review action changed one meanwhile. */
    suspend fun reviewStates(): Map<String, ReviewState> = dao.states().associate { row -> row.path to ReviewState.of(row.reviewState) }

    /**
     * Deletes every stored embedding and nothing else: the next pipeline run computes them again.
     * A plain DELETE leaves the file as big as it was, so the pages go back to the system with a
     * VACUUM, which can't run inside a transaction or while another statement is running: call
     * it when no scan is.
     */
    suspend fun deleteEmbeddings() = write {
        dao.deleteAllEmbeddings()
        withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("VACUUM") }
    }

    suspend fun embeddingCount(): Int = dao.embeddingCount()

    suspend fun imageCount(): Int = dao.imageCount()

    /**
     * What the embeddings take in the file, estimated as one page each. A 512-dimension vector is 2 KB, so with its
     * key a row fills a bit more than half a 4 KB page and the next row does not fit beside it.
     * shortcut: wrong for vectors much smaller or larger than a page, when the model changes. (`dbstat` is not in
     * Android's SQLite, so the real figure is not available.)
     */
    suspend fun embeddingBytes(): Long = dao.embeddingCount() * withContext(Dispatchers.IO) { db.openHelper.readableDatabase.pageSize }

    /** What the database takes on disk, its write-ahead log included. */
    suspend fun fileBytes(): Long = withContext(Dispatchers.IO) {
        val path = db.openHelper.readableDatabase.path ?: return@withContext 0L
        listOf("", "-wal", "-shm").sumOf { File(path + it).length() }
    }

    /** Drops what these detectors stored for these images, signals included, so a run analyzes them again. */
    suspend fun forgetAnalyses(paths: Collection<String>, detectorIds: Collection<String>) = delete {
        paths.chunked(SQL_VARIABLES).sumOf { dao.forgetAnalyses(it, detectorIds.toList()) }
    }

    suspend fun setEmbeddings(model: String, modelVersion: Int, vectors: Map<String, FloatArray>) = writeTransaction {
        for ((path, vector) in vectors) {
            requireRecorded(path)
            val bytes = ByteBuffer.allocate(4 * vector.size).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asFloatBuffer().put(vector)
            dao.putEmbedding(EmbeddingRow(path, model, modelVersion, bytes.array()))
        }
    }

    /** Every stored embedding of this model version, loaded at once for group detectors. */
    suspend fun embeddings(model: String, modelVersion: Int): Map<String, FloatArray> = dao.embeddings(model, modelVersion).associate { row ->
        val floats = ByteBuffer.wrap(row.vector).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        row.path to FloatArray(floats.remaining()).also { floats.get(it) }
    }

    suspend fun analyzedPaths(detectorId: String, detectorVersion: Int): Set<String> = dao.analyzedPaths(detectorId, detectorVersion).toSet()

    /**
     * [analyzedPaths] of several detectors, by id, from one read of the table: no index starts with the detector, so
     * each read goes through every row.
     */
    suspend fun analyzedPaths(versions: Map<String, Int>): Map<String, Set<String>> {
        if (versions.isEmpty()) return emptyMap()
        val analyzed = dao.analyses(versions.keys.toList()).filter { versions[it.detectorId] == it.detectorVersion }.groupBy({ it.detectorId }, { it.path })
        return versions.keys.associateWith { analyzed[it].orEmpty().toSet() }
    }

    suspend fun embeddedPaths(model: String, modelVersion: Int): Set<String> = dao.embeddedPaths(model, modelVersion).toSet()

    /** The images a per-image detector must read: not unreadable, and not in the trash, where the scan no longer looks. */
    suspend fun readableNotTrashed(): List<String> = dao.readableNotTrashed()

    /** Replaces whatever this detector stored for the image, from any version. */
    suspend fun setSignals(path: String, detectorId: String, detectorVersion: Int, signals: List<Signal>) = setSignalsOfMany(detectorId, detectorVersion, mapOf(path to signals))

    /** [setSignals] for many images in one transaction, as group detectors need. */
    suspend fun setSignalsOfMany(detectorId: String, detectorVersion: Int, signals: Map<String, List<Signal>>) = writeTransaction {
        for ((path, imageSignals) in signals) {
            requireRecorded(path)
            dao.deleteAnalysis(path, detectorId)
            dao.insertAnalysis(AnalysisRow(path, detectorId, detectorVersion))
            // A detector that had not run when the image was kept has nothing to be new against: its first result is seen.
            if (dao.keptWithoutDetector(path, detectorId)) {
                dao.insertKeptSignals(imageSignals.map { it.identity }.map { KeptSignalRow(path, it.kind.value, it.group, it.role) })
                dao.insertKeptDetectors(listOf(KeptDetectorRow(path, detectorId)))
            }
            dao.insertSignals(signalRows(path, imageSignals))
        }
    }

    /**
     * [setSignalsOfMany] for a group detector, which hands over every image of the library at each run although few of
     * them change. An image keeps its rows, ids included, when replacing them would leave it with the same rows in the
     * same order: the same version and signals, and those signals already after the image's others, where new ids put
     * them. The others are replaced as [setSignalsOfMany] does, and a missing image throws [NoSuchElementException] and
     * writes nothing, as there. Four reads replace the four statements an image that took. Android only: the lab writes
     * every image.
     */
    suspend fun setGroupSignals(detectorId: String, detectorVersion: Int, signals: Map<String, List<Signal>>) = writeTransaction {
        val versions = dao.analyses(listOf(detectorId)).associate { it.path to it.detectorVersion }
        // An analyses row goes with its image, so only a path without one can be missing.
        val unanalyzed = signals.keys.filter { it !in versions }
        val recorded = HashSet<String>()
        unanalyzed.chunked(SQL_VARIABLES).forEach { paths -> dao.refs(paths).mapTo(recorded) { it.path } }
        unanalyzed.firstOrNull { it !in recorded }?.let { require(0, it) }
        val stored = dao.detectorSignals(detectorId).groupBy { it.path }
        val lastOther = dao.lastOtherSignals(detectorId).associate { it.path to it.id }
        val rows = signals.mapValues { (path, imageSignals) -> signalRows(path, imageSignals) }
        val changed = rows.filter { (path, new) ->
            val old = stored[path].orEmpty()
            versions[path] != detectorVersion || old.map { it.copy(id = 0) } != new || old.isNotEmpty() && old.first().id < (lastOther[path] ?: 0)
        }
        changed.keys.filter { it in versions }.chunked(SQL_VARIABLES).forEach { dao.forgetAnalyses(it, listOf(detectorId)) }
        dao.insertAnalyses(changed.keys.map { AnalysisRow(it, detectorId, detectorVersion) })
        dao.insertSignals(changed.values.flatten())
        // A detector that had not run when the image was kept has nothing to be new against: its first result is seen.
        val kept = dao.keptWithoutDetector(detectorId).filter { it in signals }
        dao.insertKeptSignals(kept.flatMap { path -> signals.getValue(path).map { it.identity }.map { KeptSignalRow(path, it.kind.value, it.group, it.role) } })
        dao.insertKeptDetectors(kept.map { KeptDetectorRow(it, detectorId) })
    }

    suspend fun groupFingerprint(detectorId: String, detectorVersion: Int): String? = dao.groupFingerprint(detectorId, detectorVersion)?.let { groupRun(it).first() }

    /** The paths whose content hash failed in the run that stored the fingerprint. */
    suspend fun groupHashFailed(detectorId: String, detectorVersion: Int): List<String> = dao.groupFingerprint(detectorId, detectorVersion)?.let { groupRun(it).drop(1) }.orEmpty()

    /** The paths go in the fingerprint's column, as a JSON array with it, so the schema stays as it is. */
    suspend fun setGroupFingerprint(detectorId: String, detectorVersion: Int, fingerprint: String, hashFailed: Collection<String> = emptyList()) = write {
        val stored = if (hashFailed.isEmpty()) fingerprint else JSONArray(listOf(fingerprint) + hashFailed).toString()
        dao.putGroupRun(GroupRunRow(detectorId, detectorVersion, stored))
    }

    suspend fun isAnalyzed(path: String, detectorId: String, detectorVersion: Int): Boolean = dao.analyses(path, detectorId, detectorVersion) > 0

    suspend fun signals(path: String): List<Signal> = dao.signals(path).map(::signal)

    /**
     * Keeping an image also records the signals it has now: only a different one brings it back
     * among the candidates (docs/design.md §6.3).
     */
    suspend fun setReviewState(path: String, state: ReviewState) = writeTransaction {
        require(dao.setReviewState(path, state.value), path)
        dao.deleteKeptSignals(path)
        dao.deleteKeptDetectors(path)
        if (state == ReviewState.KEPT) {
            dao.keepRanDetectors(path)
            dao.insertKeptSignals(
                signals(path).map { it.identity }.map { KeptSignalRow(path, it.kind.value, it.group, it.role) },
            )
        }
    }

    /** The signals the image had when the user kept it, empty if it was never kept. */
    suspend fun keptSignals(path: String): Set<SignalIdentity> = dao.keptSignals(path).map { SignalIdentity(Kind.of(it.category), it.groupId, it.role) }.toSet()

    /** The ids of the detectors that had stored a result when the user kept it, empty if it was never kept. */
    suspend fun keptDetectors(path: String): Set<String> = dao.keptDetectors(path).toSet()

    /**
     * Puts back a state and the kept signals that went with it, as [setReviewState] cannot: it records the
     * signals the image has now. Returns false, and writes nothing, for an image that has no row.
     */
    suspend fun restoreReviewState(path: String, state: ReviewState, kept: Set<SignalIdentity>, detectors: Set<String>): Boolean = writeTransaction {
        if (dao.setReviewState(path, state.value) == 0) return@writeTransaction false
        dao.deleteKeptSignals(path)
        dao.insertKeptSignals(kept.map { KeptSignalRow(path, it.kind.value, it.group, it.role) })
        dao.deleteKeptDetectors(path)
        dao.insertKeptDetectors(detectors.map { KeptDetectorRow(path, it) })
        true
    }

    suspend fun reviewState(path: String): ReviewState {
        val value = dao.reviewState(path) ?: throw NoSuchElementException("image not in the index: $path")
        return ReviewState.of(value)
    }

    suspend fun excludedFolders(): List<String> = dao.excludedFolders()

    /** Stored without a trailing separator, which [isExcluded][io.github.bert0ns.cribrum.folders.isExcluded] relies on. */
    suspend fun excludeFolder(path: String) = write { dao.insertExcludedFolder(ExcludedFolderRow(File(path).path)) }

    suspend fun removeExcludedFolder(path: String) = write { dao.deleteExcludedFolder(path) }

    /** Every indexed image not in the trash, with its path, size, and mtime. */
    suspend fun notTrashed(): List<ImageRef> = dao.notTrashed().map { ImageRef(it.path, it.size, it.mtimeNs) }

    /** The EXIF capture time of each image that has one, to order the review by date. The ranker never sees it. A time that does not parse is left out. */
    suspend fun capturedAt(): Map<String, LocalDateTime> = dao.capturedAt().mapNotNull { row ->
        runCatching { LocalDateTime.parse(row.capturedAt) }.getOrNull()?.let { row.path to it }
    }.toMap()

    /** Every image with its review state and signals, as the ranker takes them. */
    suspend fun indexedImages(): List<IndexedImage> = traceSuspend("Index.indexedImages") {
        readTransaction {
            val signals = dao.allSignals().groupBy({ it.path }, ::signal)
            val seen = dao.allKeptSignals().groupBy({ it.path }) { SignalIdentity(Kind.of(it.category), it.groupId, it.role) }
            dao.statesNow().map { row ->
                IndexedImage(
                    ImageRef(row.path, row.size, row.mtimeNs),
                    ReviewState.of(row.reviewState),
                    signals[row.path].orEmpty(),
                    seen[row.path].orEmpty().toSet(),
                )
            }
        }
    }

    /** JPEGs, by the mime their header gave, whose headers were not read with the current rules (docs/design.md §9). */
    suspend fun jpegsToRead(): List<ImageRef> = dao.jpegsToRead(JPEG_RULES_VERSION).map { ImageRef(it.path, it.size, it.mtimeNs) }

    /** Stores what the scan read from a JPEG. Leaves [version] alone, as for `not_images`: it changes no ranking. */
    suspend fun setJpeg(path: String, info: JpegInfo) = putJpeg(path, info, null, null)

    /**
     * Stores what the compress page needs of a JPEG the app has just put at [path] itself, a compressed copy or a
     * restored original, so the page lists it before a scan reads it (docs/design.md §9). The resolution goes on the
     * `jpegs` row and not in the metadata, and [jpegsToRead] still lists the row: the scan reads the file's metadata and
     * headers, as for any changed file, and its own row replaces this one.
     */
    suspend fun setJpegBeforeScan(path: String, info: JpegInfo, width: Int, height: Int) = putJpeg(path, info, width, height)

    private suspend fun putJpeg(path: String, info: JpegInfo, width: Int?, height: Int?) = db.withTransaction {
        requireRecorded(path)
        dao.putJpeg(
            JpegRow(
                path, JPEG_RULES_VERSION, info.status.id, info.gainMap, info.quality, info.marker != null,
                info.marker?.version, info.marker?.level, info.marker?.compressedAt,
                info.losses.reEdit, info.losses.depthMap, info.losses.xmp, info.losses.c2pa, width, height,
            ),
        )
    }

    /**
     * The photos the compress page lists (docs/design.md §9): compressible on [sdk], not trashed and in no excluded
     * folder, largest first. Those with the marker are among them, for the page to show apart.
     */
    suspend fun compressible(sdk: Int): List<CompressibleImage> {
        val excluded = dao.excludedFolders()
        return dao.eligibleJpegs(JPEG_RULES_VERSION).map { row ->
            val j = row.jpeg
            val marker = if (j.compressed) Marker(j.markerVersion, j.markerLevel, j.markerCompressedAt) else null
            val info = JpegInfo(JpegStatus.of(j.status), j.gainMap, j.quality, marker, Losses(j.reEdit, j.depthMap, j.xmp, j.c2pa))
            CompressibleImage(j.path, row.size, row.width, row.height, info)
        }.filter { it.info.compressible(sdk) && !isExcluded(it.path, excluded) }
    }

    /** How many JPEGs each status has, with and without a gain map, for the log a scan writes. */
    suspend fun jpegCounts(): List<StoredJpegCount> = dao.jpegCounts()

    // The walk's memory of non-images: no image changes with it, so it leaves [version] alone, and a scan
    // that sniffed new files does not drop the review's ranking.
    override suspend fun notImages(): List<ImageRef> = dao.notImages().map { ImageRef(it.path, it.size, it.mtimeNs) }

    override suspend fun addNotImages(files: List<ImageRef>) = db.withTransaction { dao.putNotImages(files.map { NotImageRow(it.path, it.size, it.mtimeNs) }) }

    override suspend fun forgetNotImages(paths: Collection<String>) = db.withTransaction { paths.chunked(SQL_VARIABLES).forEach { dao.forgetNotImages(it) } }

    override fun close() = db.close()

    /** Bumps the version after the block, even one that throws: a read that starts after the bump finds the write committed. */
    private suspend fun <T> write(block: suspend () -> T): T = try {
        block()
    } finally {
        writes.incrementAndGet()
    }

    private suspend fun <T> writeTransaction(block: suspend () -> T): T = write { db.withTransaction(block) }

    /**
     * Runs the deletes of [block], which returns how many rows they removed, in one transaction. Unlike [write] it bumps
     * the version only when a row went, or the block threw: every scan runs these, mostly on rows that are not there, and
     * a bump drops the review's ranking (docs/design.md §4).
     */
    private suspend fun delete(block: suspend () -> Int) {
        var removed = -1
        try {
            removed = db.withTransaction(block)
        } finally {
            if (removed != 0) writes.incrementAndGet()
        }
    }

    /**
     * Runs [block] in a read-only transaction: one snapshot for all its reads, which does not take the write lock.
     * Room's `withTransaction` begins IMMEDIATE on the one writer connection, and queues behind every other Room
     * transaction, so a scan's writes would wait for the read. A deferred transaction runs on one of SQLite's reader
     * connections when the database is in WAL mode. It belongs to the thread, so [block] makes only blocking DAO calls.
     */
    private suspend fun <T> readTransaction(block: () -> T): T = withContext(Dispatchers.IO) {
        val sql = db.openHelper.writableDatabase
        sql.beginTransactionReadOnly()
        try {
            block().also { sql.setTransactionSuccessful() }
        } finally {
            sql.endTransaction()
        }
    }

    private suspend fun requireRecorded(path: String) = require(dao.count(path), path)

    private fun require(found: Int, path: String) {
        if (found == 0) throw NoSuchElementException("image not in the index: $path")
    }

    companion object {
        fun open(context: Context, name: String = "index.sqlite"): ImageIndex = ImageIndex(Room.databaseBuilder(context, IndexDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build())
    }
}

/** The lab writes Python's `isoformat()`: seconds always, no fraction for EXIF times. */
private val ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

/** Under SQLite's limit on bound parameters per statement. */
private const val SQL_VARIABLES = 500

private fun metadata(row: StoredMetadata) = ImageMetadata(
    row.width,
    row.height,
    row.mime,
    row.cameraMake,
    row.cameraModel,
    row.capturedAt?.let { LocalDateTime.parse(it) },
)

/** A fingerprint is hex, so a stored value that starts with "[" is the array [setGroupFingerprint] writes. */
private fun groupRun(stored: String): List<String> = if (stored.startsWith("[")) JSONArray(stored).let { List(it.length(), it::getString) } else listOf(stored)

private fun signalRows(path: String, signals: List<Signal>) = signals.map {
    SignalRow(
        path = path,
        detectorId = it.detectorId,
        detectorVersion = it.detectorVersion,
        category = it.kind.value,
        score = it.score.toDouble(),
        evidence = JSONObject(it.evidence).toString(),
    )
}

private fun signal(row: SignalRow) = Signal(row.detectorId, row.detectorVersion, Kind.of(row.category), row.score.toFloat(), LazyEvidence(row.evidence))

/**
 * The evidence of a stored signal, parsed on first access. The ranker reads only the kind and the score of
 * most signals, so most of the JSON objects of a large index are never parsed.
 */
private class LazyEvidence(private val json: String) : AbstractMap<String, String>() {
    private val parsed: Map<String, String> by lazy {
        val obj = JSONObject(json)
        obj.keys().asSequence().associateWith { obj.getString(it) }
    }

    override val entries: Set<Map.Entry<String, String>> get() = parsed.entries

    override fun get(key: String): String? = parsed[key]

    override fun containsKey(key: String): Boolean = parsed.containsKey(key)
}
