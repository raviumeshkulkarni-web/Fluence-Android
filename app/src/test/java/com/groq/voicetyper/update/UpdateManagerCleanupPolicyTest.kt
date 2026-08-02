package com.groq.voicetyper.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerCleanupPolicyTest {

    @Test
    fun isApkStale_unknownVersion_returnsFalse() {
        // A genuinely unknown downloaded version must never be treated as stale,
        // otherwise a background-completed download would be deleted on next launch.
        assertFalse(UpdateManager.isApkStale(downloadedVersionCode = -1, currentVersionCode = 21))
    }

    @Test
    fun isApkStale_olderDownload_returnsTrue() {
        assertTrue(UpdateManager.isApkStale(downloadedVersionCode = 20, currentVersionCode = 21))
    }

    @Test
    fun isApkStale_sameVersion_returnsTrue() {
        assertTrue(UpdateManager.isApkStale(downloadedVersionCode = 21, currentVersionCode = 21))
    }

    @Test
    fun isApkStale_newerDownload_returnsFalse() {
        assertFalse(UpdateManager.isApkStale(downloadedVersionCode = 22, currentVersionCode = 21))
    }
}
