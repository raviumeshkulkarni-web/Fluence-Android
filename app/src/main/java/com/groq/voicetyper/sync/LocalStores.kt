package com.groq.voicetyper.sync

import android.content.Context
import com.groq.voicetyper.dictionary.data.CustomDictionaryDao
import com.groq.voicetyper.history.FluenceDatabase
import com.groq.voicetyper.history.TranscriptionHistoryDao
import com.groq.voicetyper.sync.engine.DictionaryLocalStore
import com.groq.voicetyper.sync.engine.HistoryLocalStore
import com.groq.voicetyper.sync.engine.LocalStore
import com.groq.voicetyper.sync.engine.SettingsStore
import com.groq.voicetyper.sync.engine.SnippetLocalStore
import com.groq.voicetyper.sync.wire.RecordType
import java.io.File
import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide single-flight for sync passes: the in-app loop
 * ([SyncManager]) and any [SyncWorker] instance share this mutex, so a
 * WorkManager pass and a foreground pass never run concurrently (mirror of
 * Windows `running + pending_run`).
 */
object SyncPassGate {
    val mutex: Mutex = Mutex()
}

/** The one LocalStore per kind (spec §30.4 Android wiring). */
object LocalStores {
    private const val SETTINGS_FILE = "sync-settings.json"

    fun forKind(context: Context, kind: RecordType): LocalStore = when (kind) {
        RecordType.History -> HistoryLocalStore(db(context), historyDao(context))
        RecordType.Dictionary -> DictionaryLocalStore(db(context), dictionaryDao(context))
        RecordType.Snippet -> SnippetLocalStore(context.applicationContext)
        RecordType.Settings -> settingsStore(context)
    }

    fun settingsStore(context: Context): SettingsStore =
        SettingsStore(File(context.filesDir, SETTINGS_FILE).absolutePath)

    private fun db(context: Context): FluenceDatabase = FluenceDatabase.getInstance(context.applicationContext)
    private fun historyDao(context: Context): TranscriptionHistoryDao = db(context).transcriptionHistoryDao()
    private fun dictionaryDao(context: Context): CustomDictionaryDao = db(context).customDictionaryDao()
}