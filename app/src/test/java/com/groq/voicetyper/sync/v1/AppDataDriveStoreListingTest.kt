package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5 — the Drive listing carries each file's byte size so the engine skips
 * downloading known-oversized files instead of re-fetching them every pass
 * only to discard them. Unknown sizes (folders, trimmed listings) still
 * download and check as before.
 */
class AppDataDriveStoreListingTest {

    private fun store() = AppDataDriveStore("test-token")

    @Test
    fun listing_parses_sizes_string_number_absent_and_garbage() {
        val (files, _) = store().parseFileListLite(
            """{"files":[
                {"id":"a","name":"dictionary.json","version":"7","size":"123"},
                {"id":"b","name":"stats.json","size":456},
                {"id":"c","name":"snippets.json"},
                {"id":"d","name":"settings.json","size":"huge"}]}"""
        )
        assertEquals(4, files.size)
        assertEquals(123L, files[0].size)
        assertEquals(456L, files[1].size)
        // Absent or garbage sizes are unknown — never a listing failure.
        assertNull(files[2].size)
        assertNull(files[3].size)
    }

    @Test
    fun listing_reports_oversized_only_when_known_over_cap() {
        val cap = AppDataDriveStore.MAX_DOMAIN_BYTES.toLong()
        val meta = AppDataDriveStore.FileMetaLite("id", "dictionary.json", "1", cap + 1)
        assertTrue(meta.listingReportsOversized())
        assertFalse(meta.copy(size = cap).listingReportsOversized())
        assertFalse(meta.copy(size = 100L).listingReportsOversized())
        assertFalse(meta.copy(size = null).listingReportsOversized())
    }
}
