package com.groq.voicetyper.sync.v1

import java.io.ByteArrayInputStream
import java.io.InputStream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * F14 — an unbounded response body.
 *
 * The payload cap used to be checked only AFTER `resp.body?.bytes()` had already
 * buffered the entire body, so a single oversized (or simply lying) remote cost
 * one unbounded allocation before anything could refuse it. Two guards close
 * that, mirroring Windows `drive.rs`: the declared `Content-Length` is checked
 * before a byte is read, and the stream is read under a hard ceiling for the
 * case where that header is absent, chunked or wrong.
 *
 * The second, load-bearing half of the fix is the OUTCOME: an over-cap body must
 * surface as `Rejected`, never as "absent". The engine reads absent as "safe to
 * (re)create", so an over-cap body reported as absent would pick that very file
 * as the CAS write target and overwrite it.
 */
class BoundedBodyFixTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------------------
    // Pure helpers — offline, no network
    // ------------------------------------------------------------------

    /** Counts every byte handed to the reader, so "read nothing" is provable. */
    private class CountingStream(private val inner: InputStream) : InputStream() {
        var bytesRead: Int = 0
            private set

        override fun read(): Int {
            val b = inner.read()
            if (b >= 0) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }
    }

    @Test
    fun content_length_over_cap_refuses_a_declared_oversize_and_nothing_else() {
        val cap = AppDataDriveStore.MAX_DOMAIN_BYTES
        assertEquals(
            (cap + 1).toLong(),
            contentLengthOverCap((cap + 1).toLong(), cap),
        )
        assertNull("exactly at the cap is allowed", contentLengthOverCap(cap.toLong(), cap))
        assertNull("absent length falls through to the ceiling", contentLengthOverCap(null, cap))
    }

    @Test
    fun a_declared_oversize_is_refused_before_a_single_byte_is_read() {
        val cap = 1024
        val stream = CountingStream(ByteArrayInputStream(ByteArray(cap + 5000)))
        val read = readBodyCapped((cap + 1).toLong(), stream, cap)
        assertNull("an over-cap declaration must not yield a body", read)
        assertEquals(
            "guard 1 must refuse BEFORE reading, read ${stream.bytesRead} bytes",
            0,
            stream.bytesRead,
        )
    }

    @Test
    fun read_capped_returns_the_whole_body_when_it_fits() {
        val bytes = ByteArray(1024) { it.toByte() }
        val read = readCapped(ByteArrayInputStream(bytes), 2048)
        assertNotNull(read)
        assertTrue(bytes.contentEquals(read!!))
    }

    @Test
    fun read_capped_accepts_a_body_of_exactly_the_cap() {
        val read = readCapped(ByteArrayInputStream(ByteArray(64)), 64)
        assertNotNull("cap + 1 is the abort threshold, not cap", read)
        assertEquals(64, read!!.size)
    }

    @Test
    fun read_capped_stops_a_lying_content_length_body_at_the_ceiling() {
        // No declared length (chunked / absent / simply wrong): only the
        // streaming ceiling can hold this one.
        val cap = 4096
        val stream = CountingStream(ByteArrayInputStream(ByteArray(cap * 50)))
        val read = readBodyCapped(null, stream, cap)
        assertNull("a lying/absent Content-Length must still be capped", read)
        assertTrue(
            "at most cap+1 bytes may ever be pulled, pulled ${stream.bytesRead}",
            stream.bytesRead <= cap + 1,
        )
    }

    @Test
    fun a_lying_but_in_cap_declaration_is_still_measured_by_the_stream() {
        val cap = 4096
        val overCapBody = ByteArray(cap * 4)
        // Declares 10 bytes (under the cap) but streams 4x the cap.
        val read = readBodyCapped(10L, ByteArrayInputStream(overCapBody), cap)
        assertNull("a declaration under the cap cannot permit an over-cap body", read)
    }

    @Test
    fun decoded_bounded_body_rejects_a_truncated_success_body() {
        try {
            decodedBoundedBody(200, BoundedBody.Truncated)
            fail("a truncated 2xx body cannot yield valid state")
        } catch (e: SyncError.Rejected) {
            assertTrue(e.message!!.contains("cap"))
        }
    }

    @Test
    fun decoded_bounded_body_still_classifies_a_truncated_failure_body() {
        // Empty, not an error: status + headers alone must still classify the
        // failure (an unparseable 403 is Retryable, never the "not ours" latch).
        assertEquals("", decodedBoundedBody(403, BoundedBody.Truncated))
        assertEquals("", decodedBoundedBody(500, BoundedBody.Truncated))
    }

    @Test
    fun the_diagnostic_cap_is_small_but_a_success_body_keeps_the_domain_cap() {
        assertEquals(64 * 1024, AppDataDriveStore.MAX_DIAGNOSTIC_BODY_BYTES)
        assertTrue(
            "a 1000-row listing must not be clipped by the diagnostic cap",
            AppDataDriveStore.MAX_DIAGNOSTIC_BODY_BYTES < AppDataDriveStore.MAX_DOMAIN_BYTES,
        )
    }

    // ------------------------------------------------------------------
    // End to end over a local socket — still hermetic, still no Drive
    // ------------------------------------------------------------------

    /** MAX_DOMAIN_BYTES + 1 bytes without materializing them one at a time. */
    private fun overCapBuffer(): Buffer =
        Buffer().apply { repeat(1025) { write(ByteArray(8 * 1024)) } }

    private fun store() = AppDataDriveStore(
        accessToken = "test-token",
        apiBase = server.url("/drive/v3").toString(),
        uploadBase = server.url("/upload/drive/v3").toString(),
    )

    private fun dispatcher(route: (RecordedRequest) -> MockResponse?) =
        object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                route(request) ?: MockResponse().setResponseCode(404).setBody("")
        }

    private fun folderLookups(json: String) = MockResponse().setResponseCode(200).setBody(json)

    private fun listingJson(vararg files: String) = """{"files":[${files.joinToString(",")}]}"""

    /** A syntactically valid dictionary envelope that parses cleanly. */
    private fun validDictionaryJson(spoken: String = "gonna") = DomainSerializer.serializeDictionary(
        DictionaryDomain(
            entries = listOf(
                DictionaryRecord(
                    syncId = "00000000-0000-4000-8000-000000000001",
                    businessKey = DictionaryRecord.businessKeyOf(spoken),
                    spoken = spoken,
                    corrected = "going to",
                    isEnabled = true,
                    updatedAt = 1_700_000_000_000L,
                    deletedAt = null,
                    deviceId = "device-a",
                )
            )
        )
    )

    /**
     * Guard 1 end to end: the listing declares no size (so the listing-level
     * pre-check cannot help), and the media response declares a Content-Length
     * over the cap. The result must be `Rejected` — never "absent".
     */
    @Test
    fun a_declared_oversize_surfaces_as_rejected_and_never_as_absent() {
        server.dispatcher = dispatcher { req ->
            val p = req.path.orEmpty()
            when {
                p.contains("alt=media") ->
                    MockResponse().setResponseCode(200).setBody(overCapBuffer())
                p.contains("name+%3D%27v1%27") ->
                    folderLookups(listingJson("""{"id":"v1-1","name":"v1"}"""))
                p.contains("name+%3D%27fluence%27") ->
                    folderLookups(listingJson("""{"id":"fluence-1","name":"fluence"}"""))
                else ->
                    folderLookups(listingJson("""{"id":"f1","name":"dictionary.json","version":"7"}"""))
            }
        }
        try {
            val fetch = store().getDomain(DomainFile.DICTIONARY)
            fail("an over-cap body must not be reported as absent; got bytes=${fetch.bytes?.size}")
        } catch (e: SyncError.Rejected) {
            assertTrue(
                "the rejection must name the cap, was: ${e.message}",
                e.message!!.contains("cap"),
            )
        }
    }

    /**
     * Guard 2 end to end: a chunked (Content-Length-less) body that is still
     * over the cap. The streaming ceiling is the only thing holding here.
     */
    @Test
    fun a_lying_content_length_still_cannot_exceed_the_streaming_ceiling() {
        server.dispatcher = dispatcher { req ->
            val p = req.path.orEmpty()
            when {
                p.contains("alt=media") ->
                    MockResponse().setResponseCode(200).setChunkedBody(overCapBuffer(), 8 * 1024)
                p.contains("name+%3D%27v1%27") ->
                    folderLookups(listingJson("""{"id":"v1-1","name":"v1"}"""))
                p.contains("name+%3D%27fluence%27") ->
                    folderLookups(listingJson("""{"id":"fluence-1","name":"fluence"}"""))
                else ->
                    folderLookups(listingJson("""{"id":"f1","name":"dictionary.json","version":"7"}"""))
            }
        }
        try {
            val fetch = store().getDomain(DomainFile.DICTIONARY)
            fail("a chunked over-cap body must not be reported as absent; got ${fetch.bytes?.size}")
        } catch (e: SyncError.Rejected) {
            assertTrue(e.message!!.contains("cap"))
        }
    }

    /** An oversized sibling must not wedge the pass when a valid one exists. */
    @Test
    fun an_oversized_sibling_does_not_block_a_valid_sibling() {
        val valid = validDictionaryJson().toByteArray()
        server.dispatcher = dispatcher { req ->
            val p = req.path.orEmpty()
            when {
                p.startsWith("/drive/v3/files/a-big?alt=media") ->
                    MockResponse().setResponseCode(200).setBody(overCapBuffer())
                p.startsWith("/drive/v3/files/b-ok?alt=media") ->
                    MockResponse().setResponseCode(200).setBody(Buffer().write(valid))
                p.contains("name+%3D%27v1%27") ->
                    folderLookups(listingJson("""{"id":"v1-1","name":"v1"}"""))
                p.contains("name+%3D%27fluence%27") ->
                    folderLookups(listingJson("""{"id":"fluence-1","name":"fluence"}"""))
                else -> folderLookups(
                    listingJson(
                        """{"id":"a-big","name":"dictionary.json","version":"9"}""",
                        """{"id":"b-ok","name":"dictionary.json","version":"3"}""",
                    )
                )
            }
        }
        val fetch = store().getDomain(DomainFile.DICTIONARY)
        assertNotNull("the valid sibling must still be returned", fetch.bytes)
        assertEquals(valid.size, fetch.bytes!!.size)
        assertEquals("3", fetch.version)
    }

    /**
     * The shard path has the same keep-the-remote-intact requirement: an over-cap
     * shard is `Rejected` (the engine skips and retains it), never `null`
     * (which the engine would treat as absent and happily recreate).
     */
    @Test
    fun an_over_cap_stats_shard_is_rejected_and_never_null() {
        server.dispatcher = dispatcher { req ->
            val p = req.path.orEmpty()
            when {
                p.contains("alt=media") ->
                    MockResponse().setResponseCode(200).setBody(overCapBuffer())
                p.contains("name+%3D%27v1%27") ->
                    folderLookups(listingJson("""{"id":"v1-1","name":"v1"}"""))
                p.contains("name+%3D%27fluence%27") ->
                    folderLookups(listingJson("""{"id":"fluence-1","name":"fluence"}"""))
                else -> folderLookups(listingJson())
            }
        }
        try {
            val bytes = store().getStatsShardBytes("shard-1")
            fail("an over-cap shard must not read as absent; got ${bytes?.size}")
        } catch (e: SyncError.Rejected) {
            assertTrue(e.message!!.contains("cap"))
        }
    }
}