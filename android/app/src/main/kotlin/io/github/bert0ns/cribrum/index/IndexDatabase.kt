// Room tables of the image index. The schema is the lab's (lab/src/cribrum_lab/index.py), table
// by table and column by column, so both indexes mean the same thing; IndexSchemaTest checks it.
package io.github.bert0ns.cribrum.index

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "images")
data class ImageRow(
    @PrimaryKey val path: String,
    val size: Long,
    @ColumnInfo(name = "mtime_ns") val mtimeNs: Long,
    @ColumnInfo(name = "review_state", defaultValue = "'unreviewed'") val reviewState: String = "unreviewed",
    val width: Int? = null,
    val height: Int? = null,
    val mime: String? = null,
    @ColumnInfo(name = "camera_make") val cameraMake: String? = null,
    @ColumnInfo(name = "camera_model") val cameraModel: String? = null,
    @ColumnInfo(name = "captured_at") val capturedAt: String? = null,
    @ColumnInfo(defaultValue = "0") val unreadable: Boolean = false,
    @ColumnInfo(name = "content_hash") val contentHash: String? = null,
    @ColumnInfo(name = "perceptual_hash") val perceptualHash: String? = null,
)

/** Which detector version last analyzed an image, even if it emitted no signal. */
@Entity(
    tableName = "analyses",
    primaryKeys = ["path", "detector_id"],
    foreignKeys = [ForeignKey(ImageRow::class, ["path"], ["path"], onDelete = ForeignKey.CASCADE)],
)
data class AnalysisRow(
    val path: String,
    @ColumnInfo(name = "detector_id") val detectorId: String,
    @ColumnInfo(name = "detector_version") val detectorVersion: Int,
)

/** [id] is the only column the lab doesn't name: Room needs a primary key, and it is the rowid. */
@Entity(
    tableName = "signals",
    foreignKeys = [
        ForeignKey(
            AnalysisRow::class,
            ["path", "detector_id"],
            ["path", "detector_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("path", "detector_id", name = "signals_by_analysis")],
)
data class SignalRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    @ColumnInfo(name = "detector_id") val detectorId: String,
    @ColumnInfo(name = "detector_version") val detectorVersion: Int,
    val category: String,
    val score: Double,
    /** JSON object of strings. */
    val evidence: String,
)

/** Signal identities (Signal.identity) an image had when the user kept it. */
@Entity(
    tableName = "kept_signals",
    primaryKeys = ["path", "category", "group_id", "role"],
    foreignKeys = [ForeignKey(ImageRow::class, ["path"], ["path"], onDelete = ForeignKey.CASCADE)],
)
data class KeptSignalRow(
    val path: String,
    val category: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    val role: String,
)

@Entity(
    tableName = "embeddings",
    primaryKeys = ["path", "model"],
    foreignKeys = [ForeignKey(ImageRow::class, ["path"], ["path"], onDelete = ForeignKey.CASCADE)],
)
class EmbeddingRow(
    val path: String,
    val model: String,
    @ColumnInfo(name = "model_version") val modelVersion: Int,
    /** float32, little-endian. */
    val vector: ByteArray,
)

/** The image set a group detector version last ran on, so unchanged sets are skipped. */
@Entity(tableName = "group_runs")
data class GroupRunRow(
    @PrimaryKey @ColumnInfo(name = "detector_id") val detectorId: String,
    @ColumnInfo(name = "detector_version") val detectorVersion: Int,
    val fingerprint: String,
)

/** Folders the user excluded (docs/design.md §6.3). Their images are scanned but never candidates. */
@Entity(tableName = "excluded_folders")
data class ExcludedFolderRow(@PrimaryKey val path: String)

data class StoredRef(val path: String, val size: Long, @ColumnInfo(name = "mtime_ns") val mtimeNs: Long)

data class StoredMetadata(
    val width: Int,
    val height: Int,
    val mime: String?,
    @ColumnInfo(name = "camera_make") val cameraMake: String?,
    @ColumnInfo(name = "camera_model") val cameraModel: String?,
    @ColumnInfo(name = "captured_at") val capturedAt: String?,
)

data class StoredCapture(val path: String, @ColumnInfo(name = "captured_at") val capturedAt: String)

data class StoredState(
    val path: String,
    val size: Long,
    @ColumnInfo(name = "mtime_ns") val mtimeNs: Long,
    @ColumnInfo(name = "review_state") val reviewState: String,
)

@Dao
interface IndexDao {
    @Query("SELECT path, size, mtime_ns FROM images WHERE path = :path")
    suspend fun ref(path: String): StoredRef?

    @Insert
    suspend fun insertImage(image: ImageRow)

    @Query(
        """UPDATE images SET size = :size, mtime_ns = :mtimeNs, width = NULL, height = NULL,
           mime = NULL, camera_make = NULL, camera_model = NULL, captured_at = NULL,
           unreadable = 0, content_hash = NULL, perceptual_hash = NULL WHERE path = :path""",
    )
    suspend fun resetImage(path: String, size: Long, mtimeNs: Long)

    @Query("SELECT path, size, mtime_ns FROM images ORDER BY path")
    suspend fun images(): List<StoredRef>

    @Query("SELECT path, size, mtime_ns FROM images WHERE review_state != 'trashed' ORDER BY path")
    suspend fun notTrashed(): List<StoredRef>

    @Query("DELETE FROM images WHERE path IN (:paths)")
    suspend fun forget(paths: List<String>)

    @Query("SELECT path, size, mtime_ns FROM images WHERE width IS NULL AND NOT unreadable ORDER BY path")
    suspend fun withoutMetadata(): List<StoredRef>

    @Query("UPDATE images SET unreadable = 1 WHERE path = :path")
    suspend fun setUnreadable(path: String): Int

    @Query("SELECT path FROM images WHERE unreadable ORDER BY path")
    suspend fun unreadable(): List<String>

    @Query(
        """UPDATE images SET width = :width, height = :height, mime = :mime, camera_make = :cameraMake,
           camera_model = :cameraModel, captured_at = :capturedAt WHERE path = :path""",
    )
    suspend fun setMetadata(
        path: String,
        width: Int,
        height: Int,
        mime: String?,
        cameraMake: String?,
        cameraModel: String?,
        capturedAt: String?,
    ): Int

    @Query(
        """SELECT width, height, mime, camera_make, camera_model, captured_at
           FROM images WHERE path = :path AND width IS NOT NULL""",
    )
    suspend fun metadata(path: String): StoredMetadata?

    @Query("UPDATE images SET content_hash = :digest WHERE path = :path")
    suspend fun setContentHash(path: String, digest: String): Int

    @Query("SELECT content_hash FROM images WHERE path = :path")
    suspend fun contentHash(path: String): String?

    @Query("UPDATE images SET perceptual_hash = :hash WHERE path = :path")
    suspend fun setPerceptualHash(path: String, hash: String): Int

    @Query("SELECT perceptual_hash FROM images WHERE path = :path")
    suspend fun perceptualHash(path: String): String?

    @Query("SELECT count(*) FROM images WHERE path = :path")
    suspend fun count(path: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEmbedding(embedding: EmbeddingRow)

    @Query("DELETE FROM embeddings WHERE path = :path")
    suspend fun deleteEmbeddings(path: String)

    @Query("DELETE FROM embeddings")
    suspend fun deleteAllEmbeddings()

    @Query("SELECT count(*) FROM images")
    suspend fun imageCount(): Int

    @Query("SELECT count(*) FROM embeddings")
    suspend fun embeddingCount(): Int

    @Query("DELETE FROM analyses WHERE path IN (:paths) AND detector_id IN (:detectorIds)")
    suspend fun forgetAnalyses(paths: List<String>, detectorIds: List<String>)

    @Query("SELECT * FROM embeddings WHERE model = :model AND model_version = :modelVersion")
    suspend fun embeddings(model: String, modelVersion: Int): List<EmbeddingRow>

    @Query("DELETE FROM analyses WHERE path = :path")
    suspend fun deleteAnalyses(path: String)

    @Query("DELETE FROM analyses WHERE path = :path AND detector_id = :detectorId")
    suspend fun deleteAnalysis(path: String, detectorId: String)

    @Insert
    suspend fun insertAnalysis(analysis: AnalysisRow)

    @Insert
    suspend fun insertSignals(signals: List<SignalRow>)

    @Query("SELECT count(*) FROM analyses WHERE path = :path AND detector_id = :detectorId AND detector_version = :version")
    suspend fun analyses(path: String, detectorId: String, version: Int): Int

    @Query("SELECT * FROM signals WHERE path = :path ORDER BY id")
    suspend fun signals(path: String): List<SignalRow>

    @Query("SELECT * FROM signals ORDER BY id")
    suspend fun allSignals(): List<SignalRow>

    @Query("SELECT fingerprint FROM group_runs WHERE detector_id = :detectorId AND detector_version = :version")
    suspend fun groupFingerprint(detectorId: String, version: Int): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putGroupRun(run: GroupRunRow)

    @Query("UPDATE images SET review_state = :state WHERE path = :path")
    suspend fun setReviewState(path: String, state: String): Int

    @Query("SELECT review_state FROM images WHERE path = :path")
    suspend fun reviewState(path: String): String?

    @Query("DELETE FROM kept_signals WHERE path = :path")
    suspend fun deleteKeptSignals(path: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKeptSignals(signals: List<KeptSignalRow>)

    @Query("SELECT * FROM kept_signals")
    suspend fun allKeptSignals(): List<KeptSignalRow>

    @Query("SELECT path, captured_at FROM images WHERE captured_at IS NOT NULL")
    suspend fun capturedAt(): List<StoredCapture>

    @Query("SELECT path, size, mtime_ns, review_state FROM images ORDER BY path")
    suspend fun states(): List<StoredState>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExcludedFolder(folder: ExcludedFolderRow)

    @Query("DELETE FROM excluded_folders WHERE path = :path")
    suspend fun deleteExcludedFolder(path: String)

    @Query("SELECT path FROM excluded_folders ORDER BY path")
    suspend fun excludedFolders(): List<String>
}

/**
 * Bump [version] on every schema change and add a migration with a test (IndexMigrationTest).
 * The lab's schema version is separate: it refuses older files instead of migrating them.
 */
@Database(
    entities = [
        ImageRow::class, AnalysisRow::class, SignalRow::class, KeptSignalRow::class, EmbeddingRow::class,
        GroupRunRow::class, ExcludedFolderRow::class,
    ],
    version = 2,
)
abstract class IndexDatabase : RoomDatabase() {
    abstract fun dao(): IndexDao
}

/** Version 1 had no excluded folders: the user's index is kept as it is. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `excluded_folders` (`path` TEXT NOT NULL, PRIMARY KEY(`path`))")
    }
}
