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
    private var currentEngineType: OfflineEngineType? = null
    private val mutex = Mutex()

    /**
     * Returns the pipeline instance for the given engine type.
     * If the engine type changed since the last call, the old instance is released
     * and a new one is created. This ensures the pipeline always uses the correct engine.
     */
    suspend fun getInstance(
        context: Context,
        engineType: OfflineEngineType = OfflineEngineType.SENSEVOICE
    ): OfflineTranscriptionPipeline {
        return mutex.withLock {
            val currentInstance = instance
            if (currentInstance != null && currentEngineType == engineType) {
                return@withLock currentInstance
            }

            // Engine type changed or first creation — release old instance if exists
            if (currentInstance != null) {
                Log.d(TAG, "Engine type changed ($currentEngineType -> $engineType). Releasing old pipeline.")
                try {
                    currentInstance.forceRelease()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing old pipeline during engine switch", e)
                }
                instance = null
                currentEngineType = null
            }

            Log.d(TAG, "Creating new OfflineTranscriptionPipeline instance (engine: $engineType)")
            val newInstance = OfflineTranscriptionPipeline(context.applicationContext, engineType)
            instance = newInstance
            currentEngineType = engineType
            return@withLock newInstance
        }
    }

    suspend fun releaseInstance() {
        mutex.withLock {
            Log.d(TAG, "Releasing OfflineTranscriptionPipeline instance")
            instance?.forceRelease()
            instance = null
            currentEngineType = null
        }
    }
}
