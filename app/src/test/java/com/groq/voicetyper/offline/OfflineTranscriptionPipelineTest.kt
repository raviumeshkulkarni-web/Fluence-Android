package com.groq.voicetyper.offline

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineTranscriptionPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var testFilesDir: File
    private var originalChecksums = mapOf<String, String>()

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        testFilesDir = tempFolder.newFolder("files")
        every { mockContext.filesDir } returns testFilesDir
        originalChecksums = ModelAssetManager.fileChecksums
    }

    @After
    fun tearDown() {
        ModelAssetManager.fileChecksums = originalChecksums
    }

    @Test
    fun testInitialize_corruptButLargeFiles_failsCleanlyBeforeEngineLoad() = runTest {
        val modelDir = File(testFilesDir, ModelAssetManager.MODEL_DIR_NAME)
        modelDir.mkdirs()
        File(modelDir, ModelAssetManager.MODEL_FILENAME)
            .writeBytes(ByteArray(10_000_005) { 'x'.code.toByte() })
        File(modelDir, ModelAssetManager.TOKENS_FILENAME).writeText("a".repeat(1500))

        ModelAssetManager.fileChecksums = mapOf(
            ModelAssetManager.TOKENS_FILENAME to "wronghash1",
            ModelAssetManager.MODEL_FILENAME to "wronghash2"
        )

        val pipeline = OfflineTranscriptionPipeline(mockContext, OfflineEngineType.SENSEVOICE)

        val error = try {
            pipeline.initialize(modelDir.absolutePath)
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertNotNull("Expected clean IllegalStateException for corrupt model", error)
        assertTrue(error!!.message!!.contains("Re-download"))
        assertEquals(OfflineTranscriber.EngineState.UNLOADED, pipeline.engineState.value)
        assertFalse(pipeline.isReady())
    }
}
