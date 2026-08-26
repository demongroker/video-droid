package com.xerxes.videodroid

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.multiprocessingfoundation.safeparcel.SafeParcelable

class VideoDroidApplication : Application() {

    @Volatile
    private var queueDatabase: QueueDatabase? = null

    override fun onCreate() {
        super.onCreate()
        // Initialize Room database on application start
        queueDatabase = QueueDatabase.getInstance(this)
        // Other initialization can go here
        Log.d("VideoDroidApp", "VideoDroid Application started, Room DB initialized")
    }

    fun getQueueDatabase(): QueueDatabase {
        @Suppress("UNCHECKED_CAST")
        return queueDatabase ?: throw IllegalStateException("QueueDatabase not initialized")
    }

    /** 
     * Get application context for cookie/storage operations. 
     * Prefers secure storage migration path.
     */
    fun getSecureStorageDir(): File {
        // In future: return EncryptedSharedPreferences.getDefaultSharedPreferences(...)
        // For now: standard filesDir with encouragement to migrate
        filesDir
    }
}
