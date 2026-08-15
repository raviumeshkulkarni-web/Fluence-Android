package com.groq.voicetyper.sync.drive

import com.groq.voicetyper.sync.engine.FileMeta
import com.groq.voicetyper.sync.engine.SyncError
import com.groq.voicetyper.sync.wire.RecordType
import com.groq.voicetyper.sync.wire.WireRecord
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GoogleDriveStoreTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun store(accessToken: String = "at-test"): GoogleDriveStore =
        GoogleDriveStore(accessToken, client, server.url("/drive/v3").toString())

    private fun wireRecord(id: String): WireRecord = WireRecord(
        v = 1,
        id = id,
        createdAt = 1_712_000_000_000L,
        deletedAt = null,
        rtype = RecordType.History,
        text = "hello",
    )

    // ── folder ─────────────────────────────────────────────────────────────

    @Test
    fun findOrCreateFolder_reusesExistingFolder() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"files":[{"id":"folder-1","name":"Fluence Transcribe"}],"nextPageToken":null}""")
        )
        store().findOrCreateFolder()

        val request = server.takeRequest()
        assertTrue(request.path!!.contains("/drive/v3/files?q="))
        assertTrue(request.getHeader("Authorization") == "Bearer at-test")
        // No second request: the folder id is cached.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun findOrCreateFolder_createsWhenMissing() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"files":[],"nextPageToken":null}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"folder-new"}""")
        )
        store().findOrCreateFolder()

        server.takeRequest() // list
        val create = server.takeRequest()
        assertTrue(create.path!!.contains("files?fields=id"))
        val body = create.body.readUtf8()
        assertTrue(body.contains("\"name\":\"Fluence Transcribe\""))
        assertTrue(body.contains("\"mimeType\":\"application/vnd.google-apps.folder\""))
    }

    @Test
    fun findOrCreateFolder_retryableOnMissingId() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"files":[],"nextPageToken":null}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"name":"oops"}""") // no id
        )
        assertThrows(SyncError.Retryable::class.java) { store().findOrCreateFolder() }
    }

    // ── listing ────────────────────────────────────────────────────────────

    @Test
    fun listFiles_paginatesAndFiltersKindNames() {
        val folderList = MockResponse()
            .setResponseCode(200)
            .setBody("""{"files":[{"id":"folder-1","name":"Fluence Transcribe"}]}""")
        server.enqueue(folderList)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"files":[{"id":"f1","name":"history-abc.json"},{"id":"f2","name":"settings-xyz.json"}],"nextPageToken":"tok-2"}"""
                )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"files":[],"nextPageToken":null}""")
        )

        val files = store().listFiles()
        assertEquals(listOf(FileMeta("f1", "history-abc.json"), FileMeta("f2", "settings-xyz.json")), files)

        server.takeRequest() // folder listing
        val pageOne = server.takeRequest()
        assertTrue(pageOne.path!!.contains("pageSize=1000"))
        val pageTwo = server.takeRequest()
        assertTrue(pageTwo.path!!.contains("pageToken=tok-2"))
    }

    @Test
    fun listFiles_returnsEmptyOnForbiddenScope() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"files":[{"id":"folder-1","name":"Fluence Transcribe"}]}""")
        )
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}"""))
        assertEquals(emptyList<FileMeta>(), store().listFiles())
    }

    @Test
    fun listFiles_surfacesAuthRequired() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"files":[{"id":"folder-1","name":"Fluence Transcribe"}]}""")
        )
        server.enqueue(MockResponse().setResponseCode(401))
        assertThrows(SyncError.AuthRequired::class.java) { store().listFiles() }
    }

    // ── content ────────────────────────────────────────────────────────────

    @Test
    fun getContent_returnsBytesOn200() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("hello"))
        val bytes = store().getContent("f1")
        assertEquals("hello", bytes?.toString(Charsets.UTF_8))
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("/drive/v3/files/f1?alt=media"))
    }

    @Test
    fun getContent_returnsNullOn404() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(store().getContent("f1"))
    }

    @Test
    fun getContent_returnsNullOn403() {
        server.enqueue(MockResponse().setResponseCode(403))
        assertNull(store().getContent("f1"))
    }

    @Test
    fun getContent_surfacesRetryableOnRateLimit() {
        server.enqueue(MockResponse().setResponseCode(429))
        assertThrows(SyncError.Retryable::class.java) { store().getContent("f1") }
    }

    @Test
    fun getContent_surfacesRetryableOnServerError() {
        server.enqueue(MockResponse().setResponseCode(503))
        assertThrows(SyncError.Retryable::class.java) { store().getContent("f1") }
    }

    // ── writes ─────────────────────────────────────────────────────────────

    @Test
    fun createFile_postsMultipartAndReturnsId() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"files":[{"id":"folder-1","name":"Fluence Transcribe"}]}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"file-new"}""")
        )
        val id = store().createFile("history-abc.json", wireRecord("abc"))
        assertEquals("file-new", id)

        server.takeRequest() // folder listing
        val create = server.takeRequest()
        assertTrue(create.path!!.contains("uploadType=multipart"))
        val body = create.body.readUtf8()
        assertTrue(body.contains("history-abc.json"))
        assertTrue(body.contains("\"v\":1"))
    }

    @Test
    fun updateContent_patchesMedia() {
        server.enqueue(MockResponse().setResponseCode(200))
        store().updateContent("f1", wireRecord("abc"))
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertTrue(request.path!!.contains("/drive/v3/files/f1?uploadType=media"))
        assertTrue(request.body.readUtf8().contains("hello"))
    }

    @Test
    fun updateContent_surfacesRetryableOnServerError() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThrows(SyncError.Retryable::class.java) { store().updateContent("f1", wireRecord("abc")) }
    }
}
