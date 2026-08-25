package com.groq.voicetyper.sync

import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide single-flight for v1.1 sync passes: the in-app loop
 * ([SyncManager]) and any [SyncWorker] instance share this mutex, so a
 * WorkManager pass and a foreground pass never run concurrently.
 */
object SyncPassGate {
    val mutex: Mutex = Mutex()
}
