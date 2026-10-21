// Persistent image index, so later scans only process new or changed files. Mirrors
// lab/src/cribrum_lab/index.py method by method.
package io.github.bert0ns.cribrum.index

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import io.github.bert0ns.cribrum.metadata.ImageMetadata
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.IndexedImage
import io.github.bert0ns.cribrum.model.Kind
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.model.SignalIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Methods taking a path throw [NoSuchElementException] for an image that was never recorded. */
class ImageIndex(private val db: IndexDatabase) : AutoCloseable {
    /** For tests that look at the schema itself. */
    internal val database: IndexDatabase get() = db

    private val dao = db.dao()

    /**
     * Adds or updates an image. Returns true if it is new or changed.
     *
     * A changed image (different size or mtime) loses its metadata, hashes, embeddings,
     * unreadable mark and signals, which must be computed again. It keeps its review state:
     * the file may only have been touched, and a decision the user made must not be lost. A real
     * edit shows up as new signals, which the ranker treats as such.
     */
    suspend fun record(image: ImageRef): Boolean = db.withTransaction {
        val stored = dao.ref(image.path)
        when {
            stored == null -> dao.insertImage(ImageRow(image.path, image.size, image.mtimeNs))
            stored.size == image.size && stored.mtimeNs == image.mtimeNs -> return@withTransaction false
            else -> {
                dao.resetImage(image.path, image.size, image.mtimeNs)
                dao.deleteAnalyses(image.path)
                dao.deleteEmbeddings(image.path)
            }
        }
        true
    }

    suspend fun images(): List<ImageRef> = dao.images().map { ImageRef(it.path, it.size, it.mtimeNs) }

    /** Removes images from the index, with everything stored about them. Files are untouched. */
    suspend fun forget(paths: Collection<String>) = db.withTransaction {
        paths.chunked(SQL_VARIABLES).forEach { dao.forget(it) }
    }

    /** Images whose metadata was never read, leaving out the unreadable ones. */
    suspend fun withoutMetadata(): List<ImageRef> = dao.withoutMetadata().map { ImageRef(it.path, it.size, it.mtimeNs) }

    /** Marks an image whose header can't be decoded, so it isn't retried until it changes. */
    suspend fun setUnreadable(path: String) = require(dao.setUnreadable(path), path)

    suspend fun unreadable(): List<String> = dao.unreadable()

    suspend fun setMetadata(path: String, metadata: ImageMetadata) = require(
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

    suspend fun metadata(path: String): ImageMetadata? = dao.metadata(path)?.let {
        ImageMetadata(
            it.width,
            it.height,
            it.mime,
            it.cameraMake,
            it.cameraModel,
            it.capturedAt?.let { text -> LocalDateTime.parse(text) },
        )
    }

    suspend fun setContentHash(path: String, digest: String) = require(dao.setContentHash(path, digest), path)

    suspend fun contentHash(path: String): String? {
        requireRecorded(path)
        return dao.contentHash(path)
    }

    /** Stored as hex text: SQLite integers are signed and a hash may use all 64 bits. */
    suspend fun setPerceptualHash(path: String, hash: ULong) = require(dao.setPerceptualHash(path, hash.toString(16).padStart(16, '0')), path)

    suspend fun perceptualHash(path: String): ULong? {
        requireRecorded(path)
        return dao.perceptualHash(path)?.toULong(16)
    }

    /**
     * Deletes every stored embedding and nothing else: the next pipeline run computes them again.
     * A plain DELETE leaves the file as big as it was, so the pages go back to the system with a
     * VACUUM, which can't run inside a transaction or while another statement is running: call
     * it when no scan is.
     */
    suspend fun deleteEmbeddings() {
        dao.deleteAllEmbeddings()
        withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("VACUUM") }
    }

    suspend fun embeddingCount(): Int = dao.embeddingCount()

    suspend fun imageCount(): Int = dao.imageCount()

    /** Drops what these detectors stored for these images, signals included, so a run analyzes them again. */
    suspend fun forgetAnalyses(paths: Collection<String>, detectorIds: Collection<String>) = db.withTransaction {
        paths.chunked(SQL_VARIABLES).forEach { dao.forgetAnalyses(it, detectorIds.toList()) }
    }

    suspend fun setEmbeddings(model: String, modelVersion: Int, vectors: Map<String, FloatArray>) = db.withTransaction {
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

    /** Replaces whatever this detector stored for the image, from any version. */
    suspend fun setSignals(path: String, detectorId: String, detectorVersion: Int, signals: List<Signal>) = setSignalsOfMany(detectorId, detectorVersion, mapOf(path to signals))

    /** [setSignals] for many images in one transaction, as group detectors need. */
    suspend fun setSignalsOfMany(detectorId: String, detectorVersion: Int, signals: Map<String, List<Signal>>) = db.withTransaction {
        for ((path, imageSignals) in signals) {
            requireRecorded(path)
            dao.deleteAnalysis(path, detectorId)
            dao.insertAnalysis(AnalysisRow(path, detectorId, detectorVersion))
            dao.insertSignals(
                imageSignals.map {
                    SignalRow(
                        path = path,
                        detectorId = it.detectorId,
                        detectorVersion = it.detectorVersion,
                        category = it.kind.value,
                        score = it.score.toDouble(),
                        evidence = JSONObject(it.evidence).toString(),
                    )
                },
            )
        }
    }

    suspend fun groupFingerprint(detectorId: String, detectorVersion: Int): String? = dao.groupFingerprint(detectorId, detectorVersion)

    suspend fun setGroupFingerprint(detectorId: String, detectorVersion: Int, fingerprint: String) = dao.putGroupRun(GroupRunRow(detectorId, detectorVersion, fingerprint))

    suspend fun isAnalyzed(path: String, detectorId: String, detectorVersion: Int): Boolean = dao.analyses(path, detectorId, detectorVersion) > 0

    suspend fun signals(path: String): List<Signal> = dao.signals(path).map(::signal)

    /**
     * Keeping an image also records the signals it has now: only a different one brings it back
     * among the candidates (docs/design.md §6.3).
     */
    suspend fun setReviewState(path: String, state: ReviewState) = db.withTransaction {
        require(dao.setReviewState(path, state.value), path)
        dao.deleteKeptSignals(path)
        if (state == ReviewState.KEPT) {
            dao.insertKeptSignals(
                signals(path).map { it.identity }.map { KeptSignalRow(path, it.kind.value, it.group, it.role) },
            )
        }
    }

    suspend fun reviewState(path: String): ReviewState {
        val value = dao.reviewState(path) ?: throw NoSuchElementException("image not in the index: $path")
        return ReviewState.entries.first { it.value == value }
    }

    suspend fun excludedFolders(): List<String> = dao.excludedFolders()

    /** Stored without a trailing separator, which [isExcluded][io.github.bert0ns.cribrum.folders.isExcluded] relies on. */
    suspend fun excludeFolder(path: String) = dao.insertExcludedFolder(ExcludedFolderRow(File(path).path))

    suspend fun removeExcludedFolder(path: String) = dao.deleteExcludedFolder(path)

    /** Every indexed image not in the trash, with its path, size, and mtime. */
    suspend fun notTrashed(): List<ImageRef> = dao.notTrashed().map { ImageRef(it.path, it.size, it.mtimeNs) }

    /** Every image with its review state and signals, as the ranker takes them. */
    suspend fun indexedImages(): List<IndexedImage> = db.withTransaction {
        val signals = dao.allSignals().groupBy({ it.path }, ::signal)
        val seen = dao.allKeptSignals().groupBy({ it.path }) { SignalIdentity(Kind.of(it.category), it.groupId, it.role) }
        dao.states().map { row ->
            IndexedImage(
                ImageRef(row.path, row.size, row.mtimeNs),
                ReviewState.entries.first { it.value == row.reviewState },
                signals[row.path].orEmpty(),
                seen[row.path].orEmpty().toSet(),
            )
        }
    }

    override fun close() = db.close()

    private suspend fun requireRecorded(path: String) = require(dao.count(path), path)

    private fun require(found: Int, path: String) {
        if (found == 0) throw NoSuchElementException("image not in the index: $path")
    }

    companion object {
        fun open(context: Context, name: String = "index.sqlite"): ImageIndex = ImageIndex(Room.databaseBuilder(context, IndexDatabase::class.java, name).addMigrations(MIGRATION_1_2).build())
    }
}

/** The lab writes Python's `isoformat()`: seconds always, no fraction for EXIF times. */
private val ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

/** Under SQLite's limit on bound parameters per statement. */
private const val SQL_VARIABLES = 500

private fun signal(row: SignalRow): Signal {
    val json = JSONObject(row.evidence)
    val evidence = json.keys().asSequence().associateWith { json.getString(it) }
    return Signal(row.detectorId, row.detectorVersion, Kind.of(row.category), row.score.toFloat(), evidence)
}
