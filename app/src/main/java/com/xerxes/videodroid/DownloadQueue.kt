package com.xerxes.videodroid

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.migration.Migration
import androidx.room.TypeConverters
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.lang.Long
import java.util.concurrent.ConcurrentHashMap

// region Room database entities

const val DB_NAME = "videodroid_queue"

@Database(entities = [QueuedJob::class], version = 2)
abstract class QueueDatabase : RoomDatabase() {
    abstract fun queuedJobDao(): QueuedJobDao

    companion object {
        @Volatile
        private var INSTANCE: QueueDatabase? = null

        fun getInstance(context: Context): QueueDatabase {
            synchronized(this) {
                INSTANCE?.let { return it }
                INSTANCE = Room.databaseBuilder(
                    context.applicationContext,
                    QueueDatabase::class.java,
                    DB_NAME
                ).addMigrations(MIGRATION_1_2).build()
            }
        }
    }
}

@Entity(tableName = "jobs")
data class QueuedJob(
    @PrimaryKey val id: Long = System.currentTimeMillis(),
    val url: String,
    val status: String = "QUEUED",
    val dlQuality: String = "720p",
    val exportHeight: Int = 1080,
    val aspect: String = "original",
    val fps: Int = 30,
    val crf: Int = 3,
    val trimEnabled: Boolean = true,
    val trimStart: Int = 1,
    val trimEnd: Int = 1,
    val exportEnabled: Boolean = true,
    val localPath: String = "",
    val downloadedPath: String = "",
    val outputPath: String = "",
    val progress: Int = 0,
    val retryCount: Int = 0,
    val errorMessage: String = "",
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val jobType: String = "download" // "download" | "processing" | "export"
)

// Type converters for JSON serialization of FormatOptions
class TypeConverters {
    @TypeConverter
    fun fromFormatOptions(opts: com.xerxes.videodroid.FormatOptions): String {
        val JSONObject = android.util.Json
        // Serialize manually
        return ""
    }

    @TypeConverter
    fun toFormatOptions(json: String): com.xerxes.videodroid.FormatOptions {
        return com.xerxes.videodroid.FormatOptions(
            dlQuality = "720p",
            exportHeight = 1080,
            aspect = "original",
            fps = 30,
            crf = 3,
            trimEnabled = true,
            trimStart = 1,
            trimEnd = 1,
            exportEnabled = true
        )
    }
}

// endregion

// region Migration

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: QueueDatabase) {
        // Add new columns for improved state management
        // The migration adds columns with default values, so existing rows keep working
        database.execSQL("""
            ALTER TABLE jobs ADD COLUMN IF NOT EXISTS status TEXT DEFAULT 'QUEUED'
            ALTER TABLE jobs ADD COLUMN IF NOT EXISTS job_type TEXT DEFAULT 'download'
            ALTER TABLE jobs ADD COLUMN IF NOT EXISTS progress INTEGER DEFAULT 0
            ALTER TABLE jobs ADD COLUMN IF NOT EXISTS retry_count INTEGER DEFAULT 0
            ALTER TABLE jobs ADD COLUMN IF NOT EXISTS error_message TEXT DEFAULT ''
            ALTER TABLE jobs ADD COLUMN IF NOT EXISTS started_at INTEGER DEFAULT 0
            ALTER TABLE jobs ADD COLUMN IF NOT EXISTS finished_at INTEGER DEFAULT 0
        """)
    }
}

// endregion

object DownloadQueue {
    private const val TAG = "VideoDroid.Queue"

    fun enqueue(context: Context, job: QueuedJob): Long {
        val db = QueueDatabase.getInstance(context)
        val dao = db.queuedJobDao()
        return dao.insert(job)
    }

    fun peek(context: Context): QueuedJob? {
        val db = QueueDatabase.getInstance(context)
        val dao = db.queuedJobDao()
        return dao.peek()
    }

    fun completeCurrent(context: Context): QueuedJob? {
        val db = QueueDatabase.getInstance(context)
        val dao = db.queuedJobDao()
        return dao.completeCurrent()
    }

    fun size(context: Context): Int {
        val db = QueueDatabase.getInstance(context)
        val dao = db.queuedJobDao()
        return dao.size()
    }

    fun waiting(context: Context): Int {
        val db = QueueDatabase.getInstance(context)
        val dao = db.queuedJobDao()
        return dao.waiting()
    }

    fun clear(context: Context) {
        val db = QueueDatabase.getInstance(context)
        val dao = db.queuedJobDao()
        dao.clear()
    }

    fun getJobById(context: Context, id: Long): QueuedJob? {
        val db = QueueDatabase.getInstance(context)
        val dao = db.queuedJobDao()
        return dao.getById(id)
    }

    fun updateJobStatus(context: Context, id: Long, status: String, errorMessage: String = "", progress: Int = 0) {
        val db = QueueDatabase.getInstance(context)
        val dao = db.queuedJobDao()
        dao.updateStatus(id, status, errorMessage, progress)
    }

    fun resetJobs(context: Context) {
        // Reset all failed jobs to QUEUED for retry
        val db = QueueDatabase.getInstance(context)
        val dao = db.queuedJobDao()
        dao.resetFailed()
    }
}

// region Dao

@Dao
interface QueuedJobDao {
    @Insert
    fun insert(job: QueuedJob): Long

    @Query("SELECT * FROM jobs WHERE status != 'COMPLETED' AND status != 'CANCELLED' ORDER BY id ASC LIMIT 1")
    fun peek(): QueuedJob?

    @Query("SELECT * FROM jobs WHERE id = :id")
    fun getById(id: Long): QueuedJob?

    @Query("UPDATE jobs SET status = :status, error_message = :errorMessage, progress = :progress, finished_at = :finishedAt WHERE id = :id")
    fun updateStatus(id: Long, status: String, errorMessage: String, progress: Int, finishedAt: Long = 0L)

    @Query("UPDATE jobs SET status = 'QUEUED', retry_count = retry_count + 1, error_message = '', started_at = 0, finished_at = 0 WHERE status = 'FAILED'")
    fun resetFailed()
}

// endregion
