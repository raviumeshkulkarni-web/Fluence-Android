package com.groq.voicetyper.offline.v2

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Resident holder for Moonshine v2 Transcriber to eliminate 1-2s load on mic press.
 * Mirrors OfflineTranscriptionPipeline's 60s idle release.
 *
 * Idle timer now represents 60s after last active recording finishes, not from load.
 * Eviction never closes a Transcriber while it has an active stream.
 */
object MoonshineV2ResidentManager {
    private const val TAG = "MoonshineV2Resident"
    private const val IDLE_RELEASE_MS = 60_000L

    private data class Resident(
        val transcriber: Transcriber,
        val modelDir: String,
        val modelArch: Int,
        var idleJob: Job? = null,
        var activeCount: Int = 0
    )

    private val mutex = Mutex()
    private var residentSmall: Resident? = null
    private var residentMedium: Resident? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun slotFor(arch: Int): Pair<() -> Resident?, (Resident?) -> Unit> {
        return when (arch) {
            JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING -> ({ residentSmall } to { v -> residentSmall = v })
            JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING -> ({ residentMedium } to { v -> residentMedium = v })
            else -> ({ null } to {})
        }
    }

    private fun activeCountFor(arch: Int): Int = when (arch) {
        JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING -> residentSmall?.activeCount ?: 0
        JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING -> residentMedium?.activeCount ?: 0
        else -> 0
    }

    suspend fun prewarm(context: Context, type: MoonshineV2ModelType) = withContext(Dispatchers.IO) {
        if (!MoonshineV2ModelManager.isModelReady(context, type)) {
            Log.d(TAG, "prewarm skip not ready: ${type.displayName}")
            return@withContext
        }
        val dir = MoonshineV2ModelManager.getModelDir(context, type).absolutePath
        // Prewarm loads (or hits) without incrementing activeCount — idle timer runs from load time.
        getOrLoadResidentInternal(dir, type.modelArch, markActive = false)
    }

    suspend fun getOrLoadResident(modelDir: String, modelArch: Int): Transcriber? {
        return getOrLoadResidentInternal(modelDir, modelArch, markActive = true)
    }

    private suspend fun getOrLoadResidentInternal(modelDir: String, modelArch: Int, markActive: Boolean): Transcriber? {
        val (getter, setter) = slotFor(modelArch)
        mutex.withLock {
            val existing = getter()
            if (existing != null && existing.modelDir == modelDir && existing.modelArch == modelArch) {
                try {
                    if (existing.transcriber.isLoaded) {
                        if (markActive) {
                            existing.activeCount++
                            // Cancel idle while active; will be rescheduled on release.
                            existing.idleJob?.cancel()
                            existing.idleJob = null
                        } else {
                            // Prewarm hit: refresh idle if not active
                            if (existing.activeCount == 0) {
                                existing.idleJob?.cancel()
                                existing.idleJob = launchIdleRelease(modelArch)
                            }
                        }
                        Log.d(TAG, "Resident hit arch=$modelArch active=${existing.activeCount} dir=$modelDir")
                        return existing.transcriber
                    }
                } catch (_: Throwable) {
                }
                // Not loaded — drop slot
                existing.idleJob?.cancel()
                setter(null)
            }
            // Opposite-model eviction only if not active
            if (modelArch == JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING) {
                val other = residentMedium
                if (other != null && other.activeCount == 0) {
                    evict(other); residentMedium = null
                } else if (other != null) {
                    Log.w(TAG, "Skip evict medium — activeCount=${other.activeCount}")
                }
            }
            if (modelArch == JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING) {
                val other = residentSmall
                if (other != null && other.activeCount == 0) {
                    evict(other); residentSmall = null
                } else if (other != null) {
                    Log.w(TAG, "Skip evict small — activeCount=${other.activeCount}")
                }
            }

            try {
                JNI.ensureLibraryLoaded()
                val engine = Transcriber()
                try { engine.setUpdateInterval(1.0) } catch (_: Throwable) {}
                Log.d(TAG, "Resident loading arch=$modelArch dir=$modelDir")
                engine.loadFromFiles(modelDir, modelArch)
                val resident = Resident(engine, modelDir, modelArch, null, if (markActive) 1 else 0)
                // Only schedule idle if not active
                if (!markActive) resident.idleJob = launchIdleRelease(modelArch)
                setter(resident)
                Log.i(TAG, "Resident loaded arch=$modelArch active=${resident.activeCount}")
                return engine
            } catch (e: Throwable) {
                Log.e(TAG, "Resident load failed arch=$modelArch", e)
                return null
            }
        }
    }

    suspend fun notifyStreamReleased(modelArch: Int) {
        mutex.withLock {
            val (getter, _) = slotFor(modelArch)
            val r = getter() ?: return
            if (r.activeCount > 0) r.activeCount--
            Log.d(TAG, "Stream released arch=$modelArch active=${r.activeCount}")
            if (r.activeCount == 0) {
                r.idleJob?.cancel()
                r.idleJob = launchIdleRelease(modelArch)
            }
        }
    }

    private fun launchIdleRelease(arch: Int): Job {
        return scope.launch {
            delay(IDLE_RELEASE_MS)
            mutex.withLock {
                val (getter, setter) = slotFor(arch)
                val r = getter()
                if (r != null && r.activeCount == 0) {
                    Log.d(TAG, "Idle release arch=$arch after 60s")
                    evict(r)
                    setter(null)
                } else if (r != null) {
                    Log.d(TAG, "Idle timer fired but arch=$arch still active=${r.activeCount}, skipping")
                }
            }
        }
    }

    private fun evict(r: Resident) {
        r.idleJob?.cancel()
        try {
            r.transcriber.close()
        } catch (_: Throwable) {
        }
    }

    suspend fun releaseAll() {
        mutex.withLock {
            // Never evict active — keep for ongoing recording
            val small = residentSmall
            if (small != null && small.activeCount == 0) { evict(small); residentSmall = null }
            else if (small != null) Log.w(TAG, "releaseAll skip small active=${small.activeCount}")

            val medium = residentMedium
            if (medium != null && medium.activeCount == 0) { evict(medium); residentMedium = null }
            else if (medium != null) Log.w(TAG, "releaseAll skip medium active=${medium.activeCount}")
        }
    }

    suspend fun onTrimMemory() {
        releaseAll()
    }
}
