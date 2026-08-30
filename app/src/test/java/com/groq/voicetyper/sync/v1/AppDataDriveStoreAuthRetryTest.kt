package com.groq.voicetyper.sync.v1

import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The single silent 401 retry: Drive rejecting the access token mid-pass must
 * refresh it once (via [AccessTokenRefresher]) and replay
 * the request — never surface "Session expired" for ordinary token invalidation.
 * A second 401 (or a rejected refresh) stops the pass with AuthRequired.
 */
class AppDataDriveStoreAuthRetryTest {

    private lateinit var server: MockWebServer
    private lateinit var refreshCalls: AtomicInteger

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        refreshCalls = AtomicInteger(0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun store(
        refresh: (AccessTokenRefresher)? = refresherThatSucceeds(),
    ) = AppDataDriveStore(
        accessToken = STALE_TOKEN,
        tokenRefresher = refresh,
        apiBase = server.url("/drive/v3").toString(),
        uploadBase = server.url("/upload/drive/v3").toString(),
    )

    private fun refresherThatSucceeds() = AccessTokenRefresher { stale ->
        assertEquals(STALE_TOKEN, stale)
        refreshCalls.incrementAndGet()
        FRESH_TOKEN
    }

    /// The happy quiet path: request 1 hits 401, the store silently re-mints,
    /// replays the request with the fresh token, and the rest of the pass
    /// continues untouched.
    @Test
    fun first401_triggersOneSilentRefresh_thenSucceeds() {
        server.enqueue(MockResponse().setResponseCode(401).setBody(""))
        server.enqueue(fluenceFolder())
        server.enqueue(v1Folder())
        server.enqueue(emptyListing())

        val fetch = store().getDomain(DomainFile.DICTIONARY)

        assertNull(fetch.bytes)
        assertNull(fetch.version)
        assertEquals(1, refreshCalls.get())
        assertEquals(4, server.requestCount)
        val requests = (0 until server.requestCount).map { server.takeRequest() }
        assertEquals("Bearer $STALE_TOKEN", requests[0].getHeader("Authorization"))
        // The replayed request and everything after uses the fresh token.
        requests.drop(1).forEach { r ->
            assertEquals("Bearer $FRESH_TOKEN", r.getHeader("Authorization"))
        }
    }

    /// A still-401 after the one refresh is a genuine authorization problem:
    /// must surface AuthRequired, never loop minting.
    @Test
    fun second401_afterSingleRefresh_surfacesAuthRequired_withoutLooping() {
        server.enqueue(MockResponse().setResponseCode(401).setBody(""))
        server.enqueue(MockResponse().setResponseCode(401).setBody(""))

        try {
            store().getDomain(DomainFile.DICTIONARY)
            throw AssertionError("expected AuthRequired")
        } catch (e: SyncError.AuthRequired) {
            assertEquals(
                "refresh attempted exactly once, never looping",
                1,
                refreshCalls.get()
            )
        }
    }

    /// A rejected re-mint (consent needed / account removed) must stop the
    /// pass immediately with AuthRequired, not retry.
    @Test
    fun refresherRejection_surfacesAuthRequired() {
        server.enqueue(MockResponse().setResponseCode(401).setBody(""))
        val store = store(AccessTokenRefresher { _ ->
            throw SyncError.AuthRequired
        })

        try {
            store.getDomain(DomainFile.DICTIONARY)
            throw AssertionError("expected AuthRequired")
        } catch (e: SyncError.AuthRequired) {
            assertEquals(0, refreshCalls.get())
        }
    }

    /// Without a refresher the status mapping is unchanged (parity with the
    /// old behavior): a 401 is AuthRequired.
    @Test
    fun noRefresher_401_stillSurfacesAuthRequired() {
        server.enqueue(MockResponse().setResponseCode(401).setBody(""))

        try {
            store(null).getDomain(DomainFile.DICTIONARY)
            throw AssertionError("expected AuthRequired")
        } catch (e: SyncError.AuthRequired) {
            // expected
        }
    }

    private fun fluenceFolder() = MockResponse()
        .setResponseCode(200)
        .setBody("""{"files":[{"id":"fluence-1","name":"fluence"}]}""")

    private fun v1Folder() = MockResponse()
        .setResponseCode(200)
        .setBody("""{"files":[{"id":"v1-1","name":"v1"}]}""")

    private fun emptyListing() = MockResponse()
        .setResponseCode(200)
        .setBody("""{"files":[]}""")

    private companion object {
        const val STALE_TOKEN = "stale-token"
        const val FRESH_TOKEN = "fresh-token"
    }
}