package com.groq.voicetyper.offline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Singleton provider to ensure only one instance of OfflineTranscriptionPipeline exists app-wide.
 * This prevents loading the heavy transcription models multiple times concurrently.
 */
object OfflinePipelineProvider {
    private const val TAG = "OfflinePipelineProvider"
    private var instance: OfflineTranscriptionPipeline? = null
    private val mutex = Mutex()

    suspend fun getInstance(context: Context): OfflineTranscriptionPipeline {
        return mutex.withLock {
            val currentInstance = instance
            if (currentInstance != null) {
                return@withLock currentInstance
            }
            Log.d(TAG, "Creating new OfflineTranscriptionPipeline instance")
            val newInstance = OfflineTranscriptionPipeline(context.applicationContext)
            instance = newInstance
            return@withLock newInstance
        }
    }

    suspend fun releaseInstance() {
        mutex.withLock {
            Log.d(TAG, "Releasing OfflineTranscriptionPipeline instance")
            instance?.forceRelease()
            instance = null
        }
    }
}
