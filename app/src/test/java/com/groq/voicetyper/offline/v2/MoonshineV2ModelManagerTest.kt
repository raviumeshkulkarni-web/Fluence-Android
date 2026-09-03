package com.groq.voicetyper.offline.v2

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MoonshineV2ModelManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var testFilesDir: File

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        testFilesDir = tempFolder.newFolder("files")
        every { mockContext.filesDir } returns testFilesDir
    }

    @Test
    fun testModelTypeConfigurations() {
        assertEquals("moonshine_v2_small", MoonshineV2ModelType.SMALL.dirName)
        assertEquals("moonshine_v2_medium", MoonshineV2ModelType.MEDIUM.dirName)
        assertEquals(8, MoonshineV2ModelType.SMALL.fileSizes.size)
        assertEquals(8, MoonshineV2ModelType.MEDIUM.fileSizes.size)
        assertEquals(142300974L, MoonshineV2ModelType.SMALL.totalBytes)
        assertEquals(269141623L, MoonshineV2ModelType.MEDIUM.totalBytes)
        assertEquals(ai.moonshine.voice.JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING, MoonshineV2ModelType.SMALL.modelArch)
        assertEquals(ai.moonshine.voice.JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING, MoonshineV2ModelType.MEDIUM.modelArch)
    }

    @Test
    fun testIsModelReady_whenFilesMissing_returnsFalse() = runBlocking {
        assertFalse(MoonshineV2ModelManager.isModelReadySync(mockContext, MoonshineV2ModelType.SMALL))
        assertFalse(MoonshineV2ModelManager.isModelReady(mockContext, MoonshineV2ModelType.SMALL))

        assertFalse(MoonshineV2ModelManager.isModelReadySync(mockContext, MoonshineV2ModelType.MEDIUM))
        assertFalse(MoonshineV2ModelManager.isModelReady(mockContext, MoonshineV2ModelType.MEDIUM))
    }

    @Test
    fun testIsModelReady_whenVerifiedMarkerPresent() = runBlocking {
        val dir = MoonshineV2ModelManager.getModelDir(mockContext, MoonshineV2ModelType.SMALL)
        dir.mkdirs()

        // Create mock files with exact sizes
        for ((fileName, size) in MoonshineV2ModelType.SMALL.fileSizes) {
            val file = File(dir, fileName)
            file.writeBytes(ByteArray(size.toInt()) { 0 })
        }

        val marker = File(dir, MoonshineV2ModelManager.VERIFIED_MARKER)
        val expectedMarkerContent = MoonshineV2ModelType.SMALL.fileSizes.entries.sortedBy { it.key }
            .joinToString("\n") { "${it.key}:${it.value}" }
        marker.writeText(expectedMarkerContent)

        assertTrue(MoonshineV2ModelManager.isModelReadySync(mockContext, MoonshineV2ModelType.SMALL))
        assertTrue(MoonshineV2ModelManager.isModelReady(mockContext, MoonshineV2ModelType.SMALL))
    }

    @Test
    fun testDeleteModel_removesFilesAndResetsState() {
        val dir = MoonshineV2ModelManager.getModelDir(mockContext, MoonshineV2ModelType.SMALL)
        dir.mkdirs()
        File(dir, "test.bin").writeText("content")

        val deleted = MoonshineV2ModelManager.deleteModel(mockContext, MoonshineV2ModelType.SMALL)
        assertTrue(deleted)
        assertFalse(dir.exists())
        assertEquals(
            MoonshineV2ModelManager.DownloadState.IDLE,
            MoonshineV2ModelManager.getProgress(MoonshineV2ModelType.SMALL).value.state
        )
    }
}
