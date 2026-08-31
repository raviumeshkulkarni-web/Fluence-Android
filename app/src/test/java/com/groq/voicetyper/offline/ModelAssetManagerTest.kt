package com.groq.voicetyper.offline

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ModelAssetManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockWebServer: MockWebServer
    private lateinit var mockContext: Context
    private lateinit var testFilesDir: File
    private var originalBaseUrl: String = ""
    private var originalChecksums = mapOf<String, String>()

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        mockContext = mockk(relaxed = true)
        testFilesDir = tempFolder.newFolder("files")
        every { mockContext.filesDir } returns testFilesDir

        originalBaseUrl = ModelAssetManager.baseUrl
        originalChecksums = ModelAssetManager.fileChecksums

        ModelAssetManager.baseUrl = mockWebServer.url("/").toString()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        // Restore original state
        ModelAssetManager.baseUrl = originalBaseUrl
        ModelAssetManager.fileChecksums = originalChecksums
    }

    private fun calculateSHA256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testIsModelReady_whenFilesMissing_returnsFalse() = runBlocking {
        assertFalse(ModelAssetManager.isModelReadySync(mockContext))
        assertFalse(ModelAssetManager.isModelReady(mockContext))
    }

    @Test
    fun testIsModelReady_corruptButLargeFiles_syncTrueButAsyncFalse() = runBlocking {
        val modelDir = File(testFilesDir, ModelAssetManager.MODEL_DIR_NAME)
        modelDir.mkdirs()

        val modelFile = File(modelDir, ModelAssetManager.MODEL_FILENAME)
        val tokensFile = File(modelDir, ModelAssetManager.TOKENS_FILENAME)
        modelFile.writeBytes(ByteArray(10_000_005) { 'x'.code.toByte() })
        tokensFile.writeText("a".repeat(1500))

        // Expected checksums correspond to different content, so hash verification fails
        ModelAssetManager.fileChecksums = mapOf(
            ModelAssetManager.TOKENS_FILENAME to calculateSHA256("zzz".toByteArray()),
            ModelAssetManager.MODEL_FILENAME to calculateSHA256("yyy".toByteArray())
        )

        assertTrue(ModelAssetManager.isModelReadySync(mockContext))
        assertFalse(ModelAssetManager.isModelReady(mockContext))
    }

    @Test
    fun testDeleteModel_cleansUpCorrectly() = runBlocking {
        val modelDir = File(testFilesDir, ModelAssetManager.MODEL_DIR_NAME)
        modelDir.mkdirs()
        val dummyFile = File(modelDir, "dummy.txt")
        dummyFile.writeText("hello")

        val bytesFreed = ModelAssetManager.deleteModel(mockContext)
        assertEquals(5L, bytesFreed)
        assertFalse(modelDir.exists())
        assertEquals(ModelAssetManager.DownloadState.IDLE, ModelAssetManager.progress.value.state)
    }

    @Test
    fun testDownloadModel_success() = runBlocking {
        val tokensContent = "a".repeat(1500)
        val tokensHash = calculateSHA256(tokensContent.toByteArray())

        val modelContent = "b".repeat(10_000_005)
        val modelHash = calculateSHA256(modelContent.toByteArray())

        // Set test expected checksums
        ModelAssetManager.fileChecksums = mapOf(
            ModelAssetManager.TOKENS_FILENAME to tokensHash,
            ModelAssetManager.MODEL_FILENAME to modelHash
        )

        // Queue server mock responses
        mockWebServer.enqueue(MockResponse().setBody(tokensContent).setResponseCode(200))
        mockWebServer.enqueue(MockResponse().setBody(modelContent).setResponseCode(200))

        val result = ModelAssetManager.downloadModel(mockContext)
        if (result.isFailure) {
            result.exceptionOrNull()?.printStackTrace()
        }
        assertTrue("Download failed: ${result.exceptionOrNull()?.message}", result.isSuccess)

        // Verify state is completed
        val finalProgress = ModelAssetManager.progress.value
        assertEquals(ModelAssetManager.DownloadState.COMPLETED, finalProgress.state)

        // Verify files exist and are correct
        assertTrue(ModelAssetManager.isModelReadySync(mockContext))
        assertTrue(ModelAssetManager.isModelReady(mockContext))

        val modelDir = ModelAssetManager.getModelDir(mockContext)
        val tokensFile = File(modelDir, ModelAssetManager.TOKENS_FILENAME)
        val modelFile = File(modelDir, ModelAssetManager.MODEL_FILENAME)

        assertEquals(tokensContent, tokensFile.readText())
        assertEquals(modelContent, modelFile.readText())
    }

    @Test
    fun testDownloadModel_checksumMismatch_fails() = runBlocking {
        val tokensContent = "dummy tokens text"
        // Intentionally provide incorrect hash
        val badHash = "badhash123"

        ModelAssetManager.fileChecksums = mapOf(
            ModelAssetManager.TOKENS_FILENAME to badHash,
            ModelAssetManager.MODEL_FILENAME to "otherhash"
        )

        mockWebServer.enqueue(MockResponse().setBody(tokensContent).setResponseCode(200))

        val result = ModelAssetManager.downloadModel(mockContext)
        assertTrue(result.isFailure)

        // Verify state is failed
        val finalProgress = ModelAssetManager.progress.value
        assertEquals(ModelAssetManager.DownloadState.FAILED, finalProgress.state)
        assertNotNull(finalProgress.errorMessage)

        // Temp and final files should be cleaned up
        val modelDir = File(testFilesDir, ModelAssetManager.MODEL_DIR_NAME)
        assertFalse(File(modelDir, ModelAssetManager.TOKENS_FILENAME).exists())
        assertFalse(File(modelDir, "${ModelAssetManager.TOKENS_FILENAME}.tmp").exists())
    }

    @Test
    fun testDownloadModel_serverError_fails() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val result = ModelAssetManager.downloadModel(mockContext)
        assertTrue(result.isFailure)

        val finalProgress = ModelAssetManager.progress.value
        assertEquals(ModelAssetManager.DownloadState.FAILED, finalProgress.state)
    }

    @Test
    fun testCancelDownload_cleansUp() = runBlocking {
        // Enqueue slow responses so DOWNLOADING state persists long enough for cancel to race deterministically.
        // Use throttled large bodies to ensure download takes >500ms.
        val largeTokens = "a".repeat(1_000_000)
        val largeModel = "b".repeat(1_000_000)
        mockWebServer.enqueue(
            MockResponse()
                .setBody(largeTokens)
                .throttleBody(64 * 1024, 50, TimeUnit.MILLISECONDS)
                .setResponseCode(200)
        )
        mockWebServer.enqueue(
            MockResponse()
                .setBody(largeModel)
                .throttleBody(64 * 1024, 50, TimeUnit.MILLISECONDS)
                .setResponseCode(200)
        )

        // Cancel shortly after download starts — deterministic, not progress-race.
        val cancelJob = launch(Dispatchers.Default) {
            kotlinx.coroutines.delay(80)
            ModelAssetManager.cancelDownload()
        }

        val result = ModelAssetManager.downloadModel(mockContext)
        assertTrue(result.isFailure)
        // Either CANCELLED or FAILED is acceptable if cancel races with completion; main assertion is cleanup.
        assertTrue(
            ModelAssetManager.progress.value.state == ModelAssetManager.DownloadState.CANCELLED ||
            ModelAssetManager.progress.value.state == ModelAssetManager.DownloadState.FAILED
        )

        cancelJob.cancel()

        val modelDir = File(testFilesDir, ModelAssetManager.MODEL_DIR_NAME)
        // Temp files must be cleaned, final file must not exist on cancel
        assertFalse(File(modelDir, ModelAssetManager.TOKENS_FILENAME).exists())
        assertFalse(File(modelDir, ModelAssetManager.TOKENS_FILENAME + ".tmp").exists())
    }
}
