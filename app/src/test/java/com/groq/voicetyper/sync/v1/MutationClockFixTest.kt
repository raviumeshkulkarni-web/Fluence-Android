package com.groq.voicetyper.sync.v1

import android.content.Context
import com.groq.voicetyper.sync.SyncAccounts
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * #28 + #30 — `MutationClock`, the monotonic floor for local mutations.
 *
 * **#28 (identity).** The floor was derived from the LOCALLY PERSISTED email
 * (`AccountHash.of(SyncAuthSession(...).accountEmail)`). The sync pass entry
 * correctly uses the TOKEN-VERIFIED identity that `SyncManager` publishes via
 * `SyncAccounts.publishAuthenticated`, so the invariant was enforced in one
 * place and assumed here. `DriveIdentity` documents exactly what assuming it
 * costs: "a locally persisted string ... writing one account's payload into
 * another account's appDataFolder".
 *
 * **#28 (diagnostics).** Three stacked empty `catch` blocks turned an
 * unreadable database into a raw `System.currentTimeMillis()`, silently
 * dropping the floor whose whole job is to stop LWW ties, with nothing logged.
 *
 * **#30 (blocking).** `next` called `runBlocking { dao.getByHash(...) }`.
 * Compose screens launch on `Main.immediate`, so every user mutation performed
 * synchronous disk I/O on the main thread (jank, ANR exposure).
 */
class MutationClockFixTest {

    private val persistedEmail = "persisted@example.com"
    private val verifiedEmail = "verified@example.com"
    private val persistedHash = AccountHash.of(persistedEmail)!!
    private val verifiedHash = AccountHash.of(verifiedEmail)!!

    private val context: Context = mockk(relaxed = true)

    @Before
    fun setUp() {
        SyncAccounts.clearAuthentication()
        // The in-memory provisional identity a signed-in process carries
        // before any pass has verified one.
        SyncAccounts.cachedAccount = persistedEmail
        MutationClock.resetForTest()
    }

    @After
    fun tearDown() {
        MutationClock.resetForTest()
        SyncAccounts.clearAuthentication()
    }

    // ------------------------------------------------------------------
    // #28 — a metadata read failure must not degrade silently
    // ------------------------------------------------------------------

    @Test
    fun metadataReadFailureIsSurfacedNotSilentlySwallowed() = runBlocking {
        MutationClock.provisionalIdentity = { persistedHash }
        MutationClock.maxSeenReader = { _, _ -> throw IllegalStateException("database is gone") }

        val wall = System.currentTimeMillis()
        val stamp = MutationClock.next(context)

        // The stamp is still produced - refusing a user's save is worse than
        // losing the floor for one edit - but the degradation is recorded.
        assertTrue("a stamp must still be produced", stamp >= wall)
        val failure = MutationClock.lastFloorFailure
        assertNotNull("the read failure must not be silent", failure)
        assertTrue(
            "the diagnostic must name the cause: $failure",
            failure!!.contains("database is gone")
        )
    }

    @Test
    fun aSuccessfulReadClearsTheFailureDiagnostic() = runBlocking {
        MutationClock.provisionalIdentity = { persistedHash }
        MutationClock.maxSeenReader = { _, _ -> 5_000L }
        MutationClock.next(context)
        assertEquals(null, MutationClock.lastFloorFailure)

        MutationClock.maxSeenReader = { _, _ -> throw IllegalStateException("boom") }
        MutationClock.next(context)
        assertNotNull(MutationClock.lastFloorFailure)
    }

    // ------------------------------------------------------------------
    // #28 — the floor must come from the VERIFIED identity
    // ------------------------------------------------------------------

    @Test
    fun floorIsSourcedFromTheVerifiedIdentityNotThePersistedOne() = runBlocking {
        // Persisted and token-verified disagree: exactly the divergence
        // DriveIdentity documents. The old code read the PERSISTED partition.
        MutationClock.provisionalIdentity = { persistedHash }
        val requested = mutableListOf<String>()
        val maxSeenByHash = mapOf(verifiedHash to 0L, persistedHash to 1L)
        MutationClock.maxSeenReader = { _, hash ->
            requested += hash
            maxSeenByHash[hash]
        }
        SyncAccounts.publishAuthenticated(verifiedEmail)

        MutationClock.next(context)

        assertEquals("exactly one partition read", 1, requested.size)
        assertEquals(
            "the floor must be read under the identity the pass writes under",
            verifiedHash,
            requested.single()
        )
        assertTrue(
            "the unverified partition must never be consulted",
            requested.none { it == persistedHash }
        )
    }

    @Test
    fun aVerifiedFloorActuallyRaisesTheStamp() = runBlocking {
        // A backwards wall-clock jump must not let a local edit lose LWW
        // against rows this device already pushed. maxSeen is a minute ahead
        // of the wall clock so the MAX_CLOCK_SKEW_MS cap does not mask it.
        val ahead = System.currentTimeMillis() + 60_000L
        MutationClock.provisionalIdentity = { persistedHash }
        MutationClock.maxSeenReader = { _, hash -> if (hash == verifiedHash) ahead else 1L }
        SyncAccounts.publishAuthenticated(verifiedEmail)

        val stamp = MutationClock.next(context)

        assertTrue(
            "stamp $stamp must clear the verified floor $ahead",
            stamp >= ahead + 1
        )
    }

    @Test
    fun theProvisionalIdentityStillAppliesWhenNoVerifiedIdentityExists() = runBlocking {
        // Guard against over-correcting: a signed-in device that has not run a
        // pass yet has no verified identity, and must still get its floor.
        val ahead = System.currentTimeMillis() + 60_000L
        MutationClock.provisionalIdentity = { persistedHash }
        val requested = mutableListOf<String>()
        MutationClock.maxSeenReader = { _, hash ->
            requested += hash
            ahead
        }
        assertTrue("precondition: no verified identity", !SyncAccounts.tokenVerified)

        val stamp = MutationClock.next(context)

        assertEquals(listOf(persistedHash), requested)
        assertTrue("stamp $stamp must clear the provisional floor $ahead", stamp >= ahead + 1)
    }

    // ------------------------------------------------------------------
    // #30 — no blocking bridge on the mutation path
    // ------------------------------------------------------------------

    @Test
    fun nextIsASuspendingFunctionSoCallersAreNotBlocked() {
        // Compile-time proof: `next` is declared suspend, which is why every
        // dictionary/snippet call site had to be reachable from a coroutine.
        // A suspend function compiles to one that takes a Continuation.
        val method = MutationClock::class.java.declaredMethods.first { it.name == "next" }
        assertTrue(
            "MutationClock.next must be suspend - a non-suspend variant would "
                + "need runBlocking to read metadata",
            method.parameterTypes.any { it.name == "kotlin.coroutines.Continuation" }
        )
    }

    @Test
    fun theMutationPathContainsNoRunBlockingBridge() {
        // The original defect was textual: `runBlocking { dao.getByHash(...) }`
        // inside a function invoked from Main.immediate. Assert it is gone from
        // every file on that path. Comments are stripped first - the word
        // appears in prose explaining why the bridge was removed.
        val owned = listOf(
            "app/src/main/java/com/groq/voicetyper/sync/v1/V1Stores.kt",
            "app/src/main/java/com/groq/voicetyper/dictionary/DictionaryRepository.kt",
            "app/src/main/java/com/groq/voicetyper/snippets/SnippetPreferences.kt"
        )
        val offenders = owned.mapNotNull { rel ->
            val file = locate(rel) ?: error("cannot locate $rel from ${File(".").absolutePath}")
            val code = stripComments(file.readText())
            if (code.contains("runBlocking")) rel else null
        }
        assertEquals("runBlocking must not remain on the mutation path", emptyList<String>(), offenders)
    }

    /** Block comments, then line comments (`://` is a URL, not a comment). */
    private fun stripComments(text: String): String =
        text.replace(Regex("""(?s)/\*.*?\*/"""), " ")
            .lines()
            .joinToString("\n") { line ->
                val at = line.indexOf("//")
                if (at < 0 || (at > 0 && line[at - 1] == ':')) line else line.substring(0, at)
            }

    @Test
    fun nextCachedStampsWithoutTouchingDisk() {
        // The zero-I/O variant the non-suspending snippet writers use. With no
        // cached floor it degrades to the wall clock - and, unlike the old
        // body, performs no metadata read to find that out.
        MutationClock.maxSeenReader = { _, _ -> error("must not be consulted") }
        MutationClock.provisionalIdentity = { persistedHash }

        val wall = System.currentTimeMillis()
        val stamp = MutationClock.nextCached()
        assertTrue(stamp >= wall)
    }

    @Test
    fun nextCachedReusesTheFloorTheSuspendingReadCached() = runBlocking {
        val ahead = System.currentTimeMillis() + 60_000L
        MutationClock.provisionalIdentity = { persistedHash }
        MutationClock.maxSeenReader = { _, _ -> ahead }
        MutationClock.next(context)

        // Second, non-suspending read: same partition, floor already known.
        MutationClock.maxSeenReader = { _, _ -> error("must not re-read") }
        val cached = MutationClock.nextCached()
        assertTrue("cached stamp $cached must clear $ahead", cached >= ahead + 1)
    }

    private fun locate(relative: String): File? {
        var dir: File? = File(".").absoluteFile
        var depth = 0
        while (dir != null && depth < 6) {
            val direct = File(dir, relative)
            if (direct.isFile) return direct
            val fromModule = File(dir, relative.removePrefix("app/"))
            if (fromModule.isFile) return fromModule
            dir = dir.parentFile
            depth++
        }
        return null
    }
}
