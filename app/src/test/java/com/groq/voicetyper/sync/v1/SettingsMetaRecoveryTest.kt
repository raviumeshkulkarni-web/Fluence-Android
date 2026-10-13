package com.groq.voicetyper.sync.v1

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase E: the per-account settings bookkeeping document is derived state
 * (last-synced baselines), so a corrupt copy resets to fresh baselines — but
 * its bytes must still be preserved aside, never silently overwritten.
 */
class SettingsMetaRecoveryTest {

    private val hash = "a".repeat(64)
    private fun prefsFile() = "${PrefsSettingsV1Store.META_PREFS}$hash"

    @Test
    fun corrupt_settings_meta_is_quarantined_and_reseeds_fresh() {
        val store = FakePrefsStore()
        val raw = "{\"language\": {broken"
        store.fileOf(prefsFile())[PrefsSettingsV1Store.META_KEY] = raw

        val out = runBlocking { PrefsSettingsV1Store(store.context()).loadByAccount(hash) }

        assertTrue("corrupt bookkeeping must reseed, not throw", out.isEmpty())
        val quarantined = store.fileOf(prefsFile()).keys
            .filter { it.startsWith(PrefsSettingsV1Store.META_CORRUPT_PREFIX) }
        assertEquals(1, quarantined.size)
        assertEquals(raw, store.fileOf(prefsFile())[quarantined.single()])
        // The live key holds fresh parseable bookkeeping, not the corrupt bytes.
        JSONObject(store.fileOf(prefsFile())[PrefsSettingsV1Store.META_KEY] as String)
    }

    @Test
    fun missing_settings_meta_loads_without_backup() {
        val store = FakePrefsStore()

        val out = runBlocking { PrefsSettingsV1Store(store.context()).loadByAccount(hash) }

        assertTrue(out.isEmpty())
        assertTrue(
            store.fileOf(prefsFile()).keys.none { it.startsWith(PrefsSettingsV1Store.META_CORRUPT_PREFIX) }
        )
    }
}
