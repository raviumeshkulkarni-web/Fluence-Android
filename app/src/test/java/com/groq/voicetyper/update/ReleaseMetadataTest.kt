package com.groq.voicetyper.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseMetadataTest {

    @Test
    fun createReleaseMetadata_validFields_correctProperties() {
        val metadata = ReleaseMetadata(
            versionCode = 15,
            versionName = "1.5.0",
            apkName = "app-release.apk",
            apkSize = 146601906L,
            sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            minSupportedVersionCode = 10,
            mandatory = false
        )

        assertEquals(15, metadata.versionCode)
        assertEquals("1.5.0", metadata.versionName)
        assertEquals("app-release.apk", metadata.apkName)
        assertEquals(146601906L, metadata.apkSize)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", metadata.sha256)
        assertEquals(10, metadata.minSupportedVersionCode)
        assertFalse(metadata.mandatory)
    }

    @Test
    fun createReleaseMetadata_optionalFieldsDefault_nullAndFalse() {
        val metadata = ReleaseMetadata(
            versionCode = 16,
            versionName = "1.6.0",
            apkName = "app-release.apk",
            apkSize = 150000000L,
            sha256 = "abc123hash"
        )

        assertEquals(16, metadata.versionCode)
        assertEquals("1.6.0", metadata.versionName)
        assertNull(metadata.minSupportedVersionCode)
        assertFalse(metadata.mandatory)
    }

    @Test
    fun isVersionNewer_semanticComparisonEdgeCases() {
        // 1.5.9 < 1.5.10 (Integer component vs string lexicographic)
        assertTrue(GitHubUpdateRepository.isVersionNewer("1.5.10", "1.5.9"))
        assertFalse(GitHubUpdateRepository.isVersionNewer("1.5.9", "1.5.10"))

        // 1.10.0 > 1.9.9
        assertTrue(GitHubUpdateRepository.isVersionNewer("1.10.0", "1.9.9"))
        assertFalse(GitHubUpdateRepository.isVersionNewer("1.9.9", "1.10.0"))

        // v prefix handling: v1.5.0 vs 1.5.0
        assertFalse(GitHubUpdateRepository.isVersionNewer("v1.5.0", "1.5.0"))
        assertFalse(GitHubUpdateRepository.isVersionNewer("1.5.0", "v1.5.0"))

        // Prerelease suffix handling: 1.6.0-beta > 1.5.0
        assertTrue(GitHubUpdateRepository.isVersionNewer("1.6.0-beta", "1.5.0"))
        assertFalse(GitHubUpdateRepository.isVersionNewer("1.5.0", "1.6.0-beta"))

        // Same version
        assertFalse(GitHubUpdateRepository.isVersionNewer("1.5.1", "1.5.1"))
    }
}

