package com.groq.voicetyper.agent

import com.groq.voicetyper.cleanup.AiCleanupPreferences
import com.groq.voicetyper.sync.SyncAccounts
import com.groq.voicetyper.sync.v1.AccountHash
import com.groq.voicetyper.sync.v1.AccountScope
import com.groq.voicetyper.sync.v1.DeviceIdProvider
import com.groq.voicetyper.sync.v1.FakePrefsStore
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The explicit legacy claim: `unassigned -> current-account-owned`.
 *
 * Supersedes the earlier rule that a pre-account Agent/Style stays permanently
 * inert. For a user who only ever used one Google account, those records are
 * simply their own data written before local storage was partitioned by account
 * — and leaving them greyed out froze data the user could not edit or remove.
 *
 * The claim is an OWNERSHIP TRANSITION and is deliberately narrow:
 *
 *  - it never runs by itself — nothing here is reachable from a read path,
 *    sign-in, or sync;
 *  - it never chooses an account — the target is the currently authenticated
 *    hash, and an invalid/absent hash claims nothing;
 *  - it never overwrites an owned record — a legacy id that already exists in the
 *    account store is skipped and reported, leaving both sides intact;
 *  - it introduces no new sync path, envelope, or ownership mechanism. A claimed
 *    row is written exactly as [AccountScope.upsertAgent] writes one: `syncId`
 *    and `updatedAt` null, `dirty` true, so the ordinary pass stamps and uploads
 *    it.
 */
class LegacyClaimTest {

    private val emailA = "owner-a@example.com"
    private val emailB = "other@example.com"
    private val hashA = AccountHash.of(emailA)!!
    private val hashB = AccountHash.of(emailB)!!

    private val a1 = "agent:${"1".repeat(32)}"
    private val a2 = "agent:${"2".repeat(32)}"
    private val s1 = "custom:${"3".repeat(32)}"

    // ---------------- harness ----------------

    /**
     * The real stamper reads the device id through `DeviceIdProvider`, whose
     * EncryptedSharedPreferences path needs a keystore a JVM unit test cannot
     * provide — same reason `AgentStyleDirtyLifecycleTest` stubs it. Without
     * this, any thread driving the real `stampUnstamped` dies before signalling
     * its latch and the test hangs forever (a silent green suite becomes a
     * hung one, which is worse).
     */
    @Before
    fun stubDeviceId() {
        mockkObject(DeviceIdProvider)
        every { DeviceIdProvider.getDeviceId(any()) } returns "test-device"
    }

    @After
    fun unstubDeviceId() {
        unmockkObject(DeviceIdProvider)
    }

    private inline fun signedInAs(email: String?, block: () -> Unit) {
        val savedVerified = SyncAccounts.tokenVerified
        val savedAccount = SyncAccounts.cachedAccount
        SyncAccounts.clearAuthentication()
        SyncAccounts.cachedAccount = email
        try {
            block()
        } finally {
            SyncAccounts.clearAuthentication()
            if (savedVerified) SyncAccounts.publishAuthenticated(savedAccount) else {
                SyncAccounts.cachedAccount = savedAccount
            }
        }
    }

    private fun store() = FakePrefsStore()

    /**
     * Spins until [thread] is contending for a monitor (BLOCKED) or has run to
     * completion (TERMINATED). The former proves it takes the shared lock; the
     * latter proves it does not. The deadline only guards against a stalled
     * scheduler — it can never produce a false pass.
     */
    private fun awaitBlockedOrDone(thread: Thread, what: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (thread.state != Thread.State.BLOCKED && thread.state != Thread.State.TERMINATED) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("$what thread never contended (scheduler stall?)")
            }
            Thread.yield()
        }
    }

    private fun seedLegacyAgents(s: FakePrefsStore, vararg ids: String) {
        val items = ids.mapIndexed { i, id ->
            """{"id":"$id","name":"Legacy ${i + 1}","hint":"prompt ${i + 1}"}"""
        }
        s.fileOf("fluence_prefs")["agent_custom_styles"] = "[${items.joinToString(",")}]"
    }

    private fun seedLegacyStyles(s: FakePrefsStore, vararg ids: String) {
        val items = ids.mapIndexed { i, id ->
            """{"id":"$id","name":"LegacyStyle ${i + 1}","hint":"style prompt"}"""
        }
        s.fileOf("fluence_prefs")["ai_cleanup_custom_styles"] = "[${items.joinToString(",")}]"
    }

    private fun rawLegacyAgents(s: FakePrefsStore): String? =
        s.fileOf("fluence_prefs")["agent_custom_styles"] as? String

    private fun rawLegacyStyles(s: FakePrefsStore): String? =
        s.fileOf("fluence_prefs")["ai_cleanup_custom_styles"] as? String

    /**
     * A duplicate id hidden from the PARSED view must still be refused.
     *
     * `readLegacy` skips rows with a blank name/hint, so a second copy of an id can
     * be invisible to the parsed list while present on disk. The duplicate guard
     * must therefore count RAW occurrences: otherwise the id looks unique, gets
     * adopted, and `removeLegacyIds` — which edits the raw array — deletes BOTH
     * copies, destroying the hidden row while reporting one successful adoption.
     */
    @Test
    fun a_duplicate_id_hidden_from_the_parsed_view_is_still_refused() = signedInAs(emailA) {
        val s = store()
        s.fileOf("fluence_prefs")["agent_custom_styles"] =
            """[{"id":"$a1","name":"Version A","hint":"good"},""" +
            """{"id":"$a1","name":"Version B","hint":""}]"""

        val out = AgentPreferences.claimLegacyAgents(s.context(), setOf(a1))
        assertTrue(
            "an id duplicated in the RAW store must not be adopted",
            out.claimedIds.isEmpty()
        )
        assertEquals(listOf(a1 to "duplicate-legacy-id"), out.refusedIds)
        val raw = rawLegacyAgents(s) ?: ""
        assertTrue("the hidden copy must survive", raw.contains("Version B"))
        assertTrue("the visible copy must survive", raw.contains("Version A"))
    }

    /**
     * The claim COPIES, it never MOVES: the legacy row is left on disk.
     *
     * This is the guarantee that replaces the old destructive cleanup. Because the
     * account store is a whole-document read-modify-write and not every writer
     * shares the claim's monitor — the sync pass notably loads the document,
     * performs a Drive round trip, then writes a `merged` payload built from that
     * pre-network snapshot — a claim landing in that window IS discarded. Keeping
     * the legacy row is what stops that from becoming permanent loss.
     */
    @Test
    fun a_successful_claim_leaves_the_legacy_row_on_disk() = signedInAs(emailA) {
        val s = store()
        seedLegacyAgents(s, a1)

        val out = AgentPreferences.claimLegacyAgents(s.context(), setOf(a1))
        assertEquals(listOf(a1), out.claimedIds)
        assertTrue(
            "the legacy row must survive the claim",
            (rawLegacyAgents(s) ?: "").contains(a1)
        )
        // And the account row is what the user now sees, because the union puts
        // account rows first and dedupes by id.
        val view = AgentPreferences.loadCustomAgents(s.context())
        assertTrue(view.any { it.id == a1 })
    }

    // ================================================================
    // 10. THE PERMANENT-LOSS RACE (adversarial)
    // ================================================================

    /**
     * Reproduces the interleaving the reviewers identified as a data-loss bug:
     *
     *   sync stamper loads the account document -> user claims -> claim saves and
     *   reports success -> the stamper saves its OWN OLDER snapshot, discarding
     *   the claim.
     *
     * Each round races the REAL production stamper
     * (`V1Stores.agentStore().stampUnstamped`) against a real claim, both
     * released from a shared gate so neither side consistently wins thread boot.
     * Either side dropping `AccountScope.accountStoreLock` lets a stale snapshot
     * win some round and the survival assertions fail. This replaces the earlier
     * hand-rolled stand-in (proved only that the claim takes a lock) and a
     * hammer-loop variant, which was structurally vacuous: continuous stamping
     * after the claim heals any transient loss, so only the final writer matters
     * and a trailing hammer can never observe the loss.
     */
    @Test
    fun claim_cannot_be_discarded_by_a_stale_stamp_write() = signedInAs(emailA) {
        val s = store()
        // A dirty owned row, so every stamp pass actually rewrites the document
        // instead of early-returning with nothing to do.
        AccountScope.upsertAgent(s.context(), hashA, a2, "Old", "old hint")
        val store = com.groq.voicetyper.sync.v1.V1Stores.agentStore(s.context())
        // Distinct legacy ids, one per round: a claimed id cannot race twice.
        val raceIds = (0 until 40).map { "agent:race$it" }
        s.fileOf("fluence_prefs")["agent_custom_styles"] = "[" + raceIds.mapIndexed { i, id ->
            """{"id":"$id","name":"Race $i","hint":"race prompt $i"}"""
        }.joinToString(",") + "]"

        repeat(40) { round ->
            val id = raceIds[round]
            val gate = java.util.concurrent.CountDownLatch(1)
            val stamper = Thread {
                gate.await()
                kotlinx.coroutines.runBlocking { store.stampUnstamped(hashA) }
            }
            val outcome = java.util.concurrent.atomic.AtomicReference<AccountScope.ClaimOutcome>()
            val claimer = Thread {
                gate.await()
                outcome.set(AgentPreferences.claimLegacyAgents(s.context(), setOf(id)))
            }
            stamper.start()
            claimer.start()
            gate.countDown()
            stamper.join(30_000)
            claimer.join(30_000)
            assertFalse("stamper thread hung on round $round", stamper.isAlive)
            assertFalse("claimer thread hung on round $round", claimer.isAlive)

            val out = outcome.get()
            assertEquals("claim must succeed on round $round", listOf(id), out.claimedIds)
            assertTrue(
                "the claim must survive the real stamp pass on round $round",
                AccountScope.loadAgents(s.context(), hashA).any { it.id == id }
            )
        }
        assertTrue(
            "the pre-existing row must survive too",
            AccountScope.loadAgents(s.context(), hashA).any { it.id == a2 }
        )
    }

    /** The invariant in one assertion: never absent from BOTH stores. */
    @Test
    fun a_record_is_never_absent_from_both_stores() = signedInAs(emailA) {
        val s = store()
        seedLegacyAgents(s, a1)
        val out = AgentPreferences.claimLegacyAgents(s.context(), setOf(a1))
        assertEquals(listOf(a1), out.claimedIds)

        val inAccount = AccountScope.loadAgents(s.context(), hashA).any { it.id == a1 }
        val inLegacy = (rawLegacyAgents(s) ?: "").contains(a1)
        assertTrue("record vanished from both stores", inAccount || inLegacy)
    }

    // ================================================================
    // 11. Tombstone policy: DELETE wins, deterministically
    // ================================================================

    @Test
    fun a_tombstoned_account_record_refuses_the_claim_and_reports_it() = signedInAs(emailA) {
        val s = store()
        seedLegacyAgents(s, a1)
        AccountScope.upsertAgent(s.context(), hashA, a1, "Deleted", "hint")
        AccountScope.deleteAgent(s.context(), hashA, a1)
        assertTrue(
            "precondition: the account row must be tombstoned",
            AccountScope.loadAgents(s.context(), hashA).first { it.id == a1 }.deletedAt != null
        )

        val out = AgentPreferences.claimLegacyAgents(s.context(), setOf(a1))
        assertTrue("a delete must never be resurrected", out.claimedIds.isEmpty())
        assertEquals(
            "the refusal must be explicit and reported",
            listOf(a1 to "tombstoned"),
            out.refusedIds
        )
        assertTrue("must not be reported as 'already yours'", out.skippedIds.isEmpty())
        // Wire tombstone semantics untouched: the row keeps its tombstone.
        assertTrue(AccountScope.loadAgents(s.context(), hashA).first { it.id == a1 }.deletedAt != null)
        // And the legacy copy is still there — nothing destroyed.
        assertTrue((rawLegacyAgents(s) ?: "").contains(a1))
    }

    @Test
    fun style_tombstone_refuses_the_claim_too() = signedInAs(emailA) {
        val s = store()
        seedLegacyStyles(s, s1)
        AccountScope.upsertStyle(s.context(), hashA, s1, "Deleted", "hint")
        AccountScope.deleteStyle(s.context(), hashA, s1)

        val out = AiCleanupPreferences.claimLegacyStyles(s.context(), setOf(s1))
        assertTrue(out.claimedIds.isEmpty())
        assertEquals(listOf(s1 to "tombstoned"), out.refusedIds)
    }

    // ================================================================
    // 12. Duplicate legacy ids: refuse, never destroy
    // ================================================================

    @Test
    fun duplicate_legacy_ids_are_refused_rather_than_one_silently_destroyed() =
        signedInAs(emailA) {
            val s = store()
            // Two rows, same id, different content — only via a hand-edited file.
            s.fileOf("fluence_prefs")["agent_custom_styles"] =
                """[{"id":"$a1","name":"Version A","hint":"a"},""" +
                """{"id":"$a1","name":"Version B","hint":"b"}]"""

            val out = AgentPreferences.claimLegacyAgents(s.context(), setOf(a1))
            assertTrue(out.claimedIds.isEmpty())
            assertEquals(
                "exactly one refusal per id, even though two rows share it",
                listOf(a1 to "duplicate-legacy-id"),
                out.refusedIds
            )
            assertTrue(AccountScope.loadAgents(s.context(), hashA).isEmpty())
            // BOTH legacy rows survive — nothing destroyed.
            val raw = rawLegacyAgents(s) ?: ""
            assertTrue("version A must survive", raw.contains("Version A"))
            assertTrue("version B must survive", raw.contains("Version B"))
        }

    // ================================================================
    // 13. Builtin ids: refused
    // ================================================================

    @Test
    fun builtin_ids_are_refused_on_the_agent_and_style_paths() = signedInAs(emailA) {
        val s = store()
        s.fileOf("fluence_prefs")["agent_custom_styles"] =
            """[{"id":"builtin","name":"Builtin","hint":"x"}]"""
        val outA = AgentPreferences.claimLegacyAgents(s.context(), setOf("builtin"))
        assertTrue(outA.claimedIds.isEmpty())
        assertEquals(listOf("builtin" to "builtin-id"), outA.refusedIds)
        assertTrue(AccountScope.loadAgents(s.context(), hashA).isEmpty())

        s.fileOf("fluence_prefs")["ai_cleanup_custom_styles"] =
            """[{"id":"proofread","name":"Proofread","hint":"x"}]"""
        val outS = AiCleanupPreferences.claimLegacyStyles(s.context(), setOf("proofread"))
        assertTrue(outS.claimedIds.isEmpty())
        assertEquals(listOf("proofread" to "builtin-id"), outS.refusedIds)
        assertTrue(AccountScope.loadStyles(s.context(), hashA).isEmpty())
    }

    // ================================================================
    // 14. The claim never rewrites the legacy document
    // ================================================================

    @Test
    fun a_claim_leaves_every_unrelated_legacy_row_untouched() = signedInAs(emailA) {
        val s = store()
        // `broken` has a BLANK hint, so `parseAgentsJson` (used by the board) drops
        // it. Any reparse-and-rewrite cleanup would destroy it as collateral damage
        // of claiming a1. The claim does not write the legacy store at all.
        s.fileOf("fluence_prefs")["agent_custom_styles"] =
            """[{"id":"$a1","name":"Claimable","hint":"good"},""" +
            """{"id":"agent:broken","name":"NoHint","hint":""},""" +
            """{"id":"agent:extra","name":"Extra","hint":"also good"}]"""
        val before = rawLegacyAgents(s)

        val out = AgentPreferences.claimLegacyAgents(s.context(), setOf(a1))
        assertEquals(listOf(a1), out.claimedIds)

        assertEquals(
            "the legacy document must be byte-identical: the claim copies, never moves",
            before,
            rawLegacyAgents(s)
        )
    }

    @Test
    fun a_claim_leaves_an_unparseable_legacy_document_exactly_as_it_was() =
        signedInAs(emailA) {
            val s = store()
            s.fileOf("fluence_prefs")["agent_custom_styles"] = "{not json at all"
            val out = AgentPreferences.claimLegacyAgents(s.context(), setOf(a1))
            assertTrue("nothing claimable from an unparseable document", out.claimedIds.isEmpty())
            assertEquals(
                "an unparseable document must be left exactly as it was",
                "{not json at all",
                rawLegacyAgents(s)
            )
        }

    @Test
    fun style_claim_leaves_the_legacy_document_untouched() = signedInAs(emailA) {
        val s = store()
        s.fileOf("fluence_prefs")["ai_cleanup_custom_styles"] =
            """[{"id":"$s1","name":"Claimable","hint":"good"},""" +
            """{"id":"custom:blank","name":"BlankHint","hint":""}]"""
        val before = rawLegacyStyles(s)

        val out = AiCleanupPreferences.claimLegacyStyles(s.context(), setOf(s1))
        assertEquals(listOf(s1), out.claimedIds)
        assertEquals(before, rawLegacyStyles(s))
    }

    /**
     * The self-healing property that replaces the destructive cleanup.
     *
     * A sync pass can write a PRE-CLAIM snapshot that omits the claimed row —
     * `applyMergedAndClearDirty` builds its payload from a document read before
     * the Drive round trip and takes no claim monitor. No claim-side locking can
     * close that. The guarantee comes from the legacy row surviving: the record is
     * visible again and can simply be claimed a second time.
     */
    @Test
    fun a_stale_sync_write_that_drops_the_claim_leaves_the_record_recoverable() =
        signedInAs(emailA) {
            val s = store()
            seedLegacyAgents(s, a1)
            val out = AgentPreferences.claimLegacyAgents(s.context(), setOf(a1))
            assertEquals(listOf(a1), out.claimedIds)

            // A writer that never took the claim monitor now saves the document it
            // read BEFORE the claim.
            AccountScope.saveAgents(s.context(), hashA, emptyList())
            assertTrue(
                "precondition: the stale write discarded the claimed row",
                AccountScope.loadAgents(s.context(), hashA).none { it.id == a1 }
            )

            // Not lost: still in the legacy store, visible, and claimable again.
            assertTrue((rawLegacyAgents(s) ?: "").contains(a1))
            assertEquals(1, AgentPreferences.unclaimedLegacyAgentCount(s.context()))
            val retry = AgentPreferences.claimLegacyAgents(s.context(), setOf(a1))
            assertTrue(
                "the record must be claimable again after the stale write",
                retry.claimedIds.contains(a1)
            )
        }

    // ================================================================
    // 15. Refusals are non-empty outcomes (the UI relies on this)
    // ================================================================

    @Test
    fun a_refusal_is_not_reported_as_an_empty_outcome() = signedInAs(emailA) {
        val s = store()
        s.fileOf("fluence_prefs")["agent_custom_styles"] =
            """[{"id":"builtin","name":"Builtin","hint":"x"}]"""
        val out = AgentPreferences.claimLegacyAgents(s.context(), setOf("builtin"))
        assertFalse("a refusal must count as a real outcome", out.isEmpty)
        assertEquals(1, out.refusedCount)
        assertEquals(0, out.claimedCount)
    }

    // ================================================================
    // 1. Nothing is owned without an explicit claim
    // ================================================================

    @Test
    fun no_legacy_record_becomes_owned_before_an_explicit_claim() = signedInAs(emailA) {
        val s = store()
        seedLegacyAgents(s, a1)

        assertEquals("account store must start empty", 0, AccountScope.loadAgents(s.context(), hashA).size)
        assertEquals("one record is waiting", 1, AgentPreferences.unclaimedLegacyAgentCount(s.context()))

        // Reading must not adopt anything.
        repeat(3) { AgentPreferences.loadCustomAgents(s.context()) }
        AgentPreferences.getDefaultAgentId(s.context())
        AgentPreferences.resolveActiveAgent(s.context(), a1)

        assertEquals("reads must never adopt", 0, AccountScope.loadAgents(s.context(), hashA).size)
        assertEquals(1, AgentPreferences.unclaimedLegacyAgentCount(s.context()))
    }

    @Test
    fun an_unclaimed_record_stays_visible_and_non_runnable() = signedInAs(emailA) {
        val s = store()
        seedLegacyAgents(s, a1)

        val listed = AgentPreferences.loadCustomAgents(s.context()).single { it.id == a1 }
        assertFalse("still not runnable before the claim", listed.available)
        assertNotNull("still explained", listed.unavailableReason)
        assertFalse(AgentPreferences.isKnownAgent(s.context(), a1))
        assertTrue(AgentPreferences.resolveActiveAgent(s.context(), a1).isBuiltIn)
    }

    // ================================================================
    // 2. Claim -> owned
    // ================================================================

    @Test
    fun claiming_adopts_the_record_into_the_current_account() = signedInAs(emailA) {
        val s = store()
        seedLegacyAgents(s, a1)

        val outcome = AgentPreferences.claimLegacyAgents(s.context())
        assertEquals(1, outcome.claimedCount)
        assertEquals(0, outcome.skippedCount)

        val owned = AccountScope.loadAgents(s.context(), hashA).single()
        assertEquals(a1, owned.id)
        assertEquals("Legacy 1", owned.name)
        assertEquals("prompt 1", owned.hint)
        assertTrue("must be dirty so the pass stamps and uploads it", owned.dirty)
        assertNull("sync metadata starts null, exactly like upsertAgent", owned.syncId)
        assertNull(owned.updatedAt)
    }

    @Test
    fun a_claimed_record_becomes_available_runnable_and_known() = signedInAs(emailA) {
        val s = store()
        seedLegacyAgents(s, a1)
        AgentPreferences.claimLegacyAgents(s.context())

        val listed = AgentPreferences.loadCustomAgents(s.context()).single { it.id == a1 }
        assertTrue("available after the claim", listed.available)
        assertNull("no unavailability reason after the claim", listed.unavailableReason)
        assertTrue(AgentPreferences.isKnownAgent(s.context(), a1))

        val resolved = AgentPreferences.resolveActiveAgent(s.context(), a1)
        assertFalse("must run the claimed agent, not the built-in", resolved.isBuiltIn)
        assertEquals("prompt 1", resolved.hint)
    }

    @Test
    fun a_claim_copies_rather_than_moves_the_record() = signedInAs(emailA) {
        val s = store()
        seedLegacyAgents(s, a1)
        AgentPreferences.claimLegacyAgents(s.context())

        assertEquals("the account copy must exist", 1, AccountScope.loadAgents(s.context(), hashA).size)
        // The legacy row is deliberately KEPT: the claim copies, never moves. See the
        // rationale on `AgentPreferences.claimLegacyAgents` — a sync pass can discard
        // a claim by writing a pre-claim snapshot, and keeping the legacy row is what
        // stops that becoming permanent loss.
        assertTrue(
            "the legacy copy must survive the claim",
            rawLegacyAgents(s)!!.contains(a1)
        )
        assertEquals(
            "and the row is therefore no longer counted as unclaimed",
            0,
            AgentPreferences.unclaimedLegacyAgentCount(s.context())
        )
    }

    @Test
    fun multiple_legacy_records_are_all_claimed() = signedInAs(emailA) {
        val s = store()
        seedLegacyAgents(s, a1, a2)

        val outcome = AgentPreferences.claimLegacyAgents(s.context())
        assertEquals(2, outcome.claimedCount)
        assertEquals(setOf(a1, a2), AccountScope.loadAgents(s.context(), hashA).map { it.id }.toSet())
        assertEquals(0, AgentPreferences.unclaimedLegacyAgentCount(s.context()))
    }

    // ================================================================
    // 3. Claim -> editable / deletable / syncable
    // ================================================================

    @Test
    fun a_claimed_agent_can_be_edited_and_stays_owned() = signedInAs(emailA) {
        val s = store()
        seedLegacyAgents(s, a1)
        AgentPreferences.claimLegacyAgents(s.context())

        val saved = AgentPreferences.saveCustomAgent(s.context(), "Renamed", "edited prompt", id = a1)
        assertNotNull("edit must succeed on a claimed record", saved)
        assertEquals("Renamed", AccountScope.loadAgents(s.context(), hashA).single().name)
        assertTrue(AgentPreferences.loadCustomAgents(s.context()).single { it.id == a1 }.available)
    }

    @Test
    fun a_claimed_agent_can_be_deleted_and_leaves_a_tombstone() = signedInAs(emailA) {
        val s = store()
        seedLegacyAgents(s, a1)
        AgentPreferences.claimLegacyAgents(s.context())

        AgentPreferences.deleteCustomAgent(s.context(), a1)
        val row = AccountScope.loadAgents(s.context(), hashA).single()
        assertNotNull("delete must tombstone so it propagates", row.deletedAt)
        assertFalse("and must leave the list", AgentPreferences.loadCustomAgents(s.context()).any { it.id == a1 })
    }

    @Test
    fun a_claimed_record_is_eligible_for_sync() = signedInAs(emailA) {
        val s = store()
        seedLegacyAgents(s, a1)
        AgentPreferences.claimLegacyAgents(s.context())

        // Eligibility is exactly what `toLocal` keys off: dirty, or missing
        // syncId/updatedAt. That is what makes the ordinary pass stamp and PUT it.
        val row = AccountScope.loadAgents(s.context(), hashA).single()
        val toLocalDirty = row.dirty || row.syncId == null || row.updatedAt == null
        assertTrue("claimed record must be pending upload", toLocalDirty)
    }

    @Test
    fun a_claim_survives_a_restart() = signedInAs(emailA) {
        val s = store()
        seedLegacyAgents(s, a1)
        AgentPreferences.claimLegacyAgents(s.context())

        // A restart re-reads the same persisted stores; nothing is in memory.
        assertEquals(a1, AccountScope.loadAgents(s.context(), hashA).single().id)
        assertTrue(AgentPreferences.loadCustomAgents(s.context()).single().available)
        assertEquals(0, AgentPreferences.unclaimedLegacyAgentCount(s.context()))
    }

    // ================================================================
    // 4. Account safety
    // ================================================================

    @Test
    fun claiming_is_refused_entirely_when_signed_out() {
        val s = store()
        seedLegacyAgents(s, a1)

        signedInAs(null) {
            val outcome = AgentPreferences.claimLegacyAgents(s.context())
            assertEquals("nothing may be claimed while signed out", 0, outcome.claimedCount)
            assertEquals(0, AccountScope.loadAgents(s.context(), hashA).size)
            assertTrue("the legacy record must survive untouched", rawLegacyAgents(s)!!.contains(a1))
        }
    }

    @Test
    fun switching_account_before_claiming_transfers_nothing() = signedInAs(emailB) {
        val s = store()
        seedLegacyAgents(s, a1)

        // The user signed in as A, never claimed, then signed in as B.
        assertEquals("B owns nothing before any claim", 0, AccountScope.loadAgents(s.context(), hashB).size)
        assertEquals(1, AgentPreferences.unclaimedLegacyAgentCount(s.context()))

        // If B now claims, the record goes to B — the account current at the
        // moment of the action — and never to A.
        val outcome = AgentPreferences.claimLegacyAgents(s.context())
        assertEquals(1, outcome.claimedCount)
        assertEquals("B must be the owner", 1, AccountScope.loadAgents(s.context(), hashB).size)
        assertEquals("A must own nothing", 0, AccountScope.loadAgents(s.context(), hashA).size)
    }

    @Test
    fun a_record_claimed_by_one_account_is_not_visible_as_owned_to_another() = signedInAs(emailA) {
        val s = store()
        seedLegacyAgents(s, a1)
        AgentPreferences.claimLegacyAgents(s.context())

        signedInAs(emailB) {
            assertEquals("B must not see A's agent", 0, AccountScope.loadAgents(s.context(), hashB).size)
            assertFalse("nor resolve it", AgentPreferences.isKnownAgent(s.context(), a1))
        }
    }

    // ================================================================
    // 5. Duplicates / stable-id collision
    // ================================================================

    @Test
    fun a_legacy_id_already_owned_is_skipped_and_never_overwritten() = signedInAs(emailA) {
        val s = store()
        seedLegacyAgents(s, a1)
        // The account already owns this id with DIFFERENT content.
        AccountScope.saveAgents(
            s.context(), hashA,
            listOf(
                AccountScope.AccountAgent(
                    id = a1, name = "Owned Version", hint = "owned prompt",
                    syncId = "sync-owned", updatedAt = 500L,
                    deviceId = "device-a", deletedAt = null, dirty = false,
                )
            ),
        )

        val outcome = AgentPreferences.claimLegacyAgents(s.context())

        assertEquals("nothing may be claimed", 0, outcome.claimedCount)
        assertEquals("the collision must be reported, not hidden", 1, outcome.skippedCount)
        assertEquals(listOf(a1), outcome.skippedIds)

        val owned = AccountScope.loadAgents(s.context(), hashA).single()
        assertEquals("the owned record must be untouched", "Owned Version", owned.name)
        assertEquals("owned prompt", owned.hint)
        assertEquals("its sync metadata must survive", "sync-owned", owned.syncId)
        assertEquals(500L, owned.updatedAt)
        assertFalse("a skipped collision must stay dirty=false (already synced)", owned.dirty)

        assertTrue("neither side may be discarded", rawLegacyAgents(s)!!.contains(a1))
    }

    @Test
    fun a_claimed_record_shadowed_by_a_still_legacy_copy_resolves_to_the_owned_one() =
        signedInAs(emailA) {
            val s = store()
            seedLegacyAgents(s, a1)
            // Simulate a partial claim: ownership granted, legacy copy left behind.
            AccountScope.upsertAgent(s.context(), hashA, a1, "Owned", "owned prompt")

            val listed = AgentPreferences.loadCustomAgents(s.context()).single { it.id == a1 }
            assertEquals("account copy must win the id", "Owned", listed.name)
            assertTrue("and be runnable", listed.available)
        }

    // ================================================================
    // 6. Repeated claim
    // ================================================================

    @Test
    fun repeated_claiming_is_idempotent() = signedInAs(emailA) {
        val s = store()
        seedLegacyAgents(s, a1)

        val first = AgentPreferences.claimLegacyAgents(s.context())
        assertEquals(1, first.claimedCount)

        val second = AgentPreferences.claimLegacyAgents(s.context())
        assertEquals(0, second.claimedCount)
        assertTrue(
            "the second claim is skipped, not re-adopted",
            second.skippedIds.contains(a1)
        )
        assertEquals("no duplicate row", 1, AccountScope.loadAgents(s.context(), hashA).size)
    }

    @Test
    fun claiming_with_nothing_to_claim_is_a_clean_no_op() = signedInAs(emailA) {
        val s = store()
        val outcome = AgentPreferences.claimLegacyAgents(s.context())
        assertTrue(outcome.isEmpty)
        assertEquals(0, AccountScope.loadAgents(s.context(), hashA).size)
    }

    // ================================================================
    // 7. Styles mirror Agents exactly
    // ================================================================

    @Test
    fun styles_claim_with_identical_semantics() = signedInAs(emailA) {
        val s = store()
        seedLegacyStyles(s, s1)

        assertEquals(1, AiCleanupPreferences.unclaimedLegacyStyleCount(s.context()))
        assertEquals(0, AccountScope.loadStyles(s.context(), hashA).size)
        assertFalse(AiCleanupPreferences.isKnownStyle(s.context(), s1))

        val outcome = AiCleanupPreferences.claimLegacyStyles(s.context())
        assertEquals(1, outcome.claimedCount)

        val owned = AccountScope.loadStyles(s.context(), hashA).single()
        assertEquals(s1, owned.id)
        assertTrue(owned.dirty)
        assertTrue("a claimed style becomes applicable", AiCleanupPreferences.isKnownStyle(s.context(), s1))
        assertTrue(AiCleanupPreferences.loadCustomStyles(s.context()).single { it.id == s1 }.available)
        assertEquals(0, AiCleanupPreferences.unclaimedLegacyStyleCount(s.context()))

        // And it can be deleted like any other.
        AiCleanupPreferences.deleteCustomStyle(s.context(), s1)
        assertNotNull(AccountScope.loadStyles(s.context(), hashA).single().deletedAt)
    }

    @Test
    fun an_unclaimed_style_stays_non_applicable() = signedInAs(emailA) {
        val s = store()
        seedLegacyStyles(s, s1)
        val listed = AiCleanupPreferences.loadCustomStyles(s.context()).single { it.id == s1 }
        assertFalse(listed.available)
        assertNotNull(listed.unavailableReason)
        assertFalse(AiCleanupPreferences.isKnownStyle(s.context(), s1))
    }

    // ================================================================
    // 17. Pinning the REAL Android account-store writers
    // ================================================================

    /**
     * Pins that `AccountAgentV1Store.stampUnstamped` takes
     * `AccountScope.accountStoreLock` — the same monitor the claim holds.
     *
     * Fails if that lock is removed: the real writer would then run to completion
     * while the critical section is held.
     *
     * The assertion is ORDER-based, never a timeout: a fixed window proved flaky
     * under full-suite load on the Windows twin of this test. Here the writer
     * records whether it had finished by the time the holder released, so a slow
     * machine can only make the test slower, never fail it spuriously.
     */
    @Test
    fun the_real_android_stamp_writer_contends_for_the_claim_lock() = signedInAs(emailA) {
        val s = store()
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        val held = java.util.concurrent.CountDownLatch(1)
        val go = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        val finishedEarly = java.util.concurrent.atomic.AtomicBoolean(false)

        // Stands in for a claim in progress: holds the real critical section.
        val holder = Thread {
            synchronized(AccountScope.accountStoreLock) {
                // Counted down only AFTER the monitor is held.
                held.countDown()
                go.await()
                released.set(true)
            }
        }
        holder.start()
        held.await()

        // The REAL production writer, invoked through its real type.
        val writer = Thread {
            kotlinx.coroutines.runBlocking {
                com.groq.voicetyper.sync.v1.V1Stores.agentStore(s.context())
                    .stampUnstamped(hashA)
            }
            finishedEarly.set(!released.get())
            done.countDown()
        }
        writer.start()

        // Deterministic, no sleep: wait until the writer thread is actually
        // contending for the monitor (JVM reports BLOCKED while waiting on a
        // `synchronized` monitor). If stampUnstamped stops taking the lock, the
        // writer runs straight to TERMINATED without ever blocking, and the
        // assertion below fails. A fixed sleep could not distinguish "blocked"
        // from "not started yet" and proved flaky under suite load.
        awaitBlockedOrDone(writer, "stamp writer")
        go.countDown()

        done.await()
        holder.join()
        writer.join()

        assertFalse(
            "the real stamp writer ran to completion while the account-store lock " +
                "was held; if stampUnstamped does not take that lock, a claim and a " +
                "sync pass can interleave and discard the claim",
            finishedEarly.get()
        )
    }

    /**
     * A stale merge through the REAL `applyMergedAndClearDirty` discards the
     * claimed row — and the record stays recoverable, because the claim copied
     * rather than moved it. This is what makes the residual race safe without
     * touching general B2/R1.
     */
    @Test
    fun a_stale_real_android_merge_that_drops_a_claim_leaves_it_recoverable() =
        signedInAs(emailA) {
            val s = store()
            seedLegacyAgents(s, a1)
            assertEquals(
                listOf(a1),
                AgentPreferences.claimLegacyAgents(s.context(), setOf(a1)).claimedIds
            )

            // The real merge writer, handed the payload it read BEFORE the claim.
            kotlinx.coroutines.runBlocking {
                com.groq.voicetyper.sync.v1.V1Stores.agentStore(s.context())
                    .applyMergedAndClearDirty(hashA, "test-device", emptyList())
            }
            assertTrue(
                "precondition: the stale merge discarded the claimed row",
                AccountScope.loadAgents(s.context(), hashA).none { it.id == a1 }
            )

            // Not lost: visible again and claimable.
            assertTrue((rawLegacyAgents(s) ?: "").contains(a1))
            val retry = AgentPreferences.claimLegacyAgents(s.context(), setOf(a1))
            assertTrue("the record can be claimed again", retry.claimedIds.contains(a1))
        }

    // ================================================================
    // 16. Delete still deletes (the claim's kept shadow must not resurrect)
    // ================================================================

    /**
     * Regression test for a bug the "copy, never move" decision would otherwise
     * introduce: the claim leaves the legacy row behind, so a delete would leave
     * that row as a device-local record that becomes visible and RUNNABLE again
     * when the user signs out. The user deletes an agent, signs out, and it is back.
     *
     * The delete path removes the shadow once the account store provably holds the
     * tombstone.
     */
    @Test
    fun deleting_a_claimed_agent_does_not_resurrect_it_on_sign_out() = signedInAs(emailA) {
        val s = store()
        seedLegacyAgents(s, a1)
        assertEquals(listOf(a1), AgentPreferences.claimLegacyAgents(s.context(), setOf(a1)).claimedIds)
        assertTrue("precondition: the claim kept the legacy row", (rawLegacyAgents(s) ?: "").contains(a1))

        AgentPreferences.deleteCustomAgent(s.context(), a1)

        assertTrue(
            "the tombstone is in the account store",
            AccountScope.loadAgents(s.context(), hashA).first { it.id == a1 }.deletedAt != null
        )
        assertFalse(
            "the legacy shadow must be gone, or signing out brings the agent back",
            (rawLegacyAgents(s) ?: "").contains(a1)
        )
    }

    @Test
    fun deleting_a_claimed_style_does_not_resurrect_it_on_sign_out() = signedInAs(emailA) {
        val s = store()
        seedLegacyStyles(s, s1)
        assertEquals(listOf(s1), AiCleanupPreferences.claimLegacyStyles(s.context(), setOf(s1)).claimedIds)
        assertTrue("precondition: the claim kept the legacy row", (rawLegacyStyles(s) ?: "").contains(s1))

        AiCleanupPreferences.deleteCustomStyle(s.context(), s1)

        assertTrue(
            "the tombstone is in the account store",
            AccountScope.loadStyles(s.context(), hashA).first { it.id == s1 }.deletedAt != null
        )
        assertFalse(
            "the legacy shadow must be gone",
            (rawLegacyStyles(s) ?: "").contains(s1)
        )
    }

    /**
     * And the signed-out view agrees: with the tombstone present and the shadow
     * gone, nothing is listed or resolvable for that id.
     */
    @Test
    fun a_deleted_claimed_record_is_not_resolvable_when_signed_out() = signedInAs(emailA) {
        val s = store()
        seedLegacyAgents(s, a1)
        AgentPreferences.claimLegacyAgents(s.context(), setOf(a1))
        AgentPreferences.deleteCustomAgent(s.context(), a1)

        signedInAs(null) {
            assertTrue(
                "a deleted agent must not reappear when signed out",
                AgentPreferences.loadCustomAgents(s.context()).none { it.id == a1 }
            )
        }
    }

    // ================================================================
    // 17. Delete durability: the tombstone report gates the shadow
    // ================================================================

    /**
     * A tombstone write that never lands must neither report success nor drop
     * the legacy shadow: the shadow is the only copy left, and removing it
     * would lose the record with no tombstone to propagate.
     */
    @Test
    fun delete_preserves_the_legacy_shadow_when_the_tombstone_write_fails() = signedInAs(emailA) {
        val s = store()
        AccountScope.upsertAgent(s.context(), hashA, a1, "Owned", "owned hint")
        seedLegacyAgents(s, a1)
        assertFalse(
            "unknown id: nothing tombstoned, nothing reported",
            AccountScope.deleteAgent(s.context(), hashA, "agent:${"9".repeat(32)}")
        )

        s.failCommits = true
        assertFalse(
            "a tombstone that never landed must not report success",
            AccountScope.deleteAgent(s.context(), hashA, a1)
        )
        // GHOST, asserted explicitly: a real `commit()` mutates the in-memory map
        // before the disk write, so the tombstone IS readable here even though it
        // was never persisted. That is precisely why the gate must not read
        // "did the value change?" but the returned flag — and why the legacy shadow
        // must be preserved: it is the only DURABLE copy left.
        assertNotNull(
            "a failed commit still leaves the tombstone readable in memory — " +
                "this is the real SharedPreferences ghost the fake now models",
            AccountScope.loadAgents(s.context(), hashA).first { it.id == a1 }.deletedAt
        )

        // And through the real delete path the shadow survives too.
        AgentPreferences.deleteCustomAgent(s.context(), a1)
        assertTrue(
            "the legacy shadow is the only copy left and must survive",
            (rawLegacyAgents(s) ?: "").contains(a1)
        )
        assertFalse(
            "a delete that could not be persisted must not report success",
            AgentPreferences.deleteCustomAgent(s.context(), a1)
        )

        s.failCommits = false
        AgentPreferences.deleteCustomAgent(s.context(), a1)
        assertTrue(
            "tombstone present after a durable delete",
            AccountScope.loadAgents(s.context(), hashA).first { it.id == a1 }.deletedAt != null
        )
        assertFalse(
            "shadow dropped once the tombstone is durable",
            (rawLegacyAgents(s) ?: "").contains(a1)
        )
    }

    @Test
    fun style_delete_preserves_the_legacy_shadow_when_the_tombstone_write_fails() =
        signedInAs(emailA) {
            val s = store()
            AccountScope.upsertStyle(s.context(), hashA, s1, "Owned", "owned hint")
            seedLegacyStyles(s, s1)

            s.failCommits = true
            assertFalse(
                "a tombstone that never landed must not report success",
                AccountScope.deleteStyle(s.context(), hashA, s1)
            )
            // The in-memory ghost exists even though nothing was persisted — same
            // real-SharedPreferences semantics as the agent path.
            assertNotNull(
                "a failed commit still leaves the tombstone readable in memory",
                AccountScope.loadStyles(s.context(), hashA).first { it.id == s1 }.deletedAt
            )
            AiCleanupPreferences.deleteCustomStyle(s.context(), s1)
            assertTrue(
                "the legacy shadow is the only copy left and must survive",
                (rawLegacyStyles(s) ?: "").contains(s1)
            )
            assertFalse(
                "a delete that could not be persisted must not report success",
                AiCleanupPreferences.deleteCustomStyle(s.context(), s1)
            )

            s.failCommits = false
            AiCleanupPreferences.deleteCustomStyle(s.context(), s1)
            assertTrue(
                "tombstone present after a durable delete",
                AccountScope.loadStyles(s.context(), hashA).first { it.id == s1 }.deletedAt != null
            )
            assertFalse(
                "shadow dropped once the tombstone is durable",
                (rawLegacyStyles(s) ?: "").contains(s1)
            )
        }

    /**
     * A claim whose durable write fails leaves a GHOST: the owned row is readable
     * in memory but was never persisted.
     *
     * This is real Android behaviour (`commitToMemory()` runs before the disk
     * write), and the fake models it deliberately. The consequences are accepted
     * and pinned here rather than assumed away:
     *
     *  - the claim reports NOTHING, so the UI cannot claim success;
     *  - the legacy row survives untouched, so nothing is lost — a restart heals
     *    the record back to unclaimed and claimable;
     *  - until then the ghost row is readable as OWNED, so the unclaimed counter
     *    reads 0. That is cosmetic, not a safety problem: the legacy copy is
     *    still there to be claimed again, and nothing can be lost.
     */
    @Test
    fun a_claim_whose_durable_write_fails_reports_nothing_and_keeps_the_legacy_row() =
        signedInAs(emailA) {
            val s = store()
            seedLegacyAgents(s, a1)
            s.failCommits = true

            val out = AgentPreferences.claimLegacyAgents(s.context(), setOf(a1))

            assertEquals(
                "nothing may be reported claimed when the durable write failed",
                emptyList<String>(),
                out.claimedIds
            )
            assertTrue(
                "the legacy row is the only durable copy and must survive",
                (rawLegacyAgents(s) ?: "").contains(a1)
            )
            assertEquals(
                "the ghost owned row is readable in memory until a restart, so the " +
                    "counter reads 0 — cosmetic only; the legacy row above is what " +
                    "guarantees nothing is lost",
                0,
                AgentPreferences.unclaimedLegacyAgentCount(s.context())
            )

            // Recovery: a fresh store (restart) sees no owned row, and the legacy
            // record is claimable again.
            val restarted = store()
            restarted.fileOf("fluence_prefs")["agent_custom_styles"] =
                rawLegacyAgents(s)
            assertEquals(
                "after a restart the record is unclaimed again and re-claimable",
                1,
                AgentPreferences.unclaimedLegacyAgentCount(restarted.context())
            )
        }

    // ================================================================
    // 18. Durable-gating: a delete that did not persist must not also
    //     undo the user's selections.
    // ================================================================
    //
    // The record is still live when the tombstone fails, so anything that pointed
    // AT it is still valid. Clearing it would silently change the user's setup in
    // exchange for a delete that did not happen. These pin the `durable` gates.

    @Test
    fun a_failed_tombstone_keeps_the_default_agent_selected() = signedInAs(emailA) {
        val s = store()
        AccountScope.upsertAgent(s.context(), hashA, a1, "Owned", "owned hint")
        AgentPreferences.setDefaultAgentId(s.context(), a1)
        assertEquals(
            "precondition: the owned agent is the default",
            a1,
            s.fileOf("fluence_prefs")["agent_default_id"]
        )

        s.failCommits = true
        assertFalse(
            "a delete that could not be persisted must not report success",
            AgentPreferences.deleteCustomAgent(s.context(), a1)
        )
        assertEquals(
            "the agent is still live, so clearing its default would drop a valid " +
                "selection for a delete that never happened",
            a1,
            s.fileOf("fluence_prefs")["agent_default_id"]
        )
    }

    @Test
    fun a_failed_tombstone_keeps_per_app_style_overrides() = signedInAs(emailA) {
        val s = store()
        AccountScope.upsertStyle(s.context(), hashA, s1, "Owned", "owned hint")
        AiCleanupPreferences.setOverride(s.context(), "com.example.app", s1)
        assertEquals(
            "precondition: the app is bound to the style",
            s1,
            AiCleanupPreferences.getOverrides(s.context())["com.example.app"]
        )

        s.failCommits = true
        assertFalse(
            "a delete that could not be persisted must not report success",
            AiCleanupPreferences.deleteCustomStyle(s.context(), s1)
        )
        assertEquals(
            "the style is still live, so sending its apps back to Auto would " +
                "unbind the user for a delete that never happened",
            s1,
            AiCleanupPreferences.getOverrides(s.context())["com.example.app"]
        )
    }
}