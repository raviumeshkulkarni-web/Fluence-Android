package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 production path — the read snapshot that the real agent read path
 * uses. Mirrors the Windows `VisibleAgents` tests.
 *
 * The four wiring conditions are asserted here against the *production* types,
 * not helpers:
 *  1. one snapshot serves listing and resolution;
 *  2. null resolves to builtin behaviour, never an error;
 *  3. a tombstoned record never yields a usable hint;
 *  4. duplicate-id LWW consistency is deferred (not claimed here).
 */
class VisibleAgentsSnapshotTest {

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    private fun owned(id: String, name: String, deletedAt: Long? = null) =
        AccountScope.AccountAgent(
            id = id, name = name, hint = "hint-$name",
            syncId = "sync-$id", updatedAt = 100L, deviceId = "dev-a",
            deletedAt = deletedAt
        )

    private fun leg(id: String, name: String) =
        AccountScope.VisibleAgent.Legacy(id = id, name = name, hint = "hint-$name")

    /**
     * STAGE 6: a snapshot always carries a VERIFIED account hash in production
     * (`unionAgentsFor` opens no account store without one). Tests that build a
     * union by hand must therefore pass one, or `Owned` records are correctly
     * treated as having no attributable owner and withheld.
     */
    private fun snapshot(
        legacy: List<AccountScope.VisibleAgent.Legacy> = emptyList(),
        account: List<AccountScope.AccountAgent> = emptyList(),
        hash: String? = hashA
    ): AccountScope.VisibleAgentsSnapshot {
        val union = AccountScope.unionAgents(legacy, account)
        return AccountScope.VisibleAgentsSnapshot.forTest(union, hash)
    }

    // ---- condition 2: null -> builtin, never an error ----

    @Test
    fun null_resolves_to_builtin() {
        val s = snapshot(listOf(leg("agent:1", "L1")))
        assertNull(s.resolve(null))
        assertNull("no hint means run the built-in", s.hint(null))
    }

    @Test
    fun builtin_unknown_and_absent_all_resolve_to_builtin() {
        val s = snapshot(listOf(leg("agent:1", "L1")))
        assertNull(s.resolve(AccountScope.LEGACY_BUILT_IN_ID))
        assertNull(s.resolve("agent:nope"))
        assertNull(s.resolve(null))
    }

    @Test
    fun visible_record_resolves_with_its_hint() {
        val s = snapshot(listOf(leg("agent:1", "L1")), listOf(owned("agent:2", "A2")))
        // STAGE 6: a legacy record of unknown provenance does not resolve while
        // an account is signed in. Only the owned record supplies a hint.
        assertNull(s.hint("agent:1"))
        assertEquals("hint-A2", s.hint("agent:2"))
        assertEquals("agent:2", s.resolve("agent:2")?.id)
    }

    // ---- condition 3: tombstone never yields a usable hint ----

    @Test
    fun tombstoned_record_never_yields_a_usable_hint() {
        val s = snapshot(
            listOf(leg("agent:1", "L1")),
            listOf(owned("agent:2", "gone", deletedAt = 1_700_000_000_000L))
        )
        assertNull("a deleted agent must not resolve", s.resolve("agent:2"))
        assertNull("a deleted agent must never leak a hint", s.hint("agent:2"))
    }

    @Test
    fun tombstone_does_not_fall_through_to_shadowed_legacy() {
        // The tombstone wins the id; the device-local record must NOT become the
        // resolved agent, which would resurrect a deleted agent.
        val s = snapshot(
            listOf(leg("agent:X", "Legacy Agent")),
            listOf(owned("agent:X", "deleted", deletedAt = 42L))
        )
        assertNull(s.resolve("agent:X"))
        assertNull(s.hint("agent:X"))
    }

    // ---- condition 1: one snapshot for listing and resolution ----

    @Test
    fun one_snapshot_serves_listing_and_resolution() {
        // STAGE 6 sharpened this invariant. Listing and resolution share ONE
        // snapshot, but display is deliberately a superset of what can run: an
        // unassigned legacy record is listed (so it does not look deleted) while
        // being non-runnable. The invariant is therefore about RUNNABILITY — every
        // record the UI marks runnable must be known and must resolve — not about
        // every listed id being executable.
        val s = snapshot(listOf(leg("agent:1", "L1")), listOf(owned("agent:2", "A2")))
        val shown = s.displayRecords()
        assertEquals(listOf("agent:2", "agent:1"), shown.map { it.id })
        shown.forEach { record ->
            assertEquals(
                "runnability must agree for ${record.id}",
                s.isRunnable(record),
                s.isKnown(record.id) && s.resolve(record.id) != null,
            )
        }
        assertTrue("the runnable record must resolve", s.isRunnable(shown.first { it.id == "agent:2" }))
        assertFalse("the unassigned record must not be runnable", s.isRunnable(shown.first { it.id == "agent:1" }))
    }

    @Test
    fun is_known_covers_builtin_and_admitted_ids_only() {
        val s = snapshot(listOf(leg("agent:1", "L1")), listOf(owned("agent:2", "A2")))
        assertTrue(s.isKnown(AccountScope.LEGACY_BUILT_IN_ID))
        assertTrue("account records are executable", s.isKnown("agent:2"))
        assertTrue(!s.isKnown("agent:other"))
        // STAGE 6: a legacy record is DISPLAYABLE but not executable, so it must
        // not be reported as a known agent. Were it "known", a saved default
        // pointing at it could cause an unknown-provenance prompt to run.
        assertFalse(
            "an unassigned legacy record must not be executable",
            s.isKnown("agent:1"),
        )
    }

    // ---- display projection vs sync view (Reviewer 2's required-before-delete-wiring) ----

    @Test
    fun display_omits_tombstones_but_records_retains_them_for_sync() {
        // The two views must differ: sync needs the tombstone (it prevents
        // resurrection), the UI must not show a deleted agent as live.
        val s = snapshot(
            listOf(leg("agent:1", "L1")),
            listOf(owned("agent:2", "A2"), owned("agent:3", "gone", deletedAt = 42L))
        )
        val recordIds = s.records().map { it.id }
        val displayIds = s.displayRecords().map { it.id }
        assertTrue("sync view must retain the tombstone", recordIds.contains("agent:3"))
        assertTrue("display must hide the tombstone", !displayIds.contains("agent:3"))
        assertEquals(listOf("agent:2", "agent:1"), displayIds)
    }

    @Test
    fun display_omits_a_tombstone_without_resurrecting_its_shadowed_legacy() {
        // The tombstone wins the id and is hidden from the display, so the
        // shadowed device-local record must NOT take its place.
        val s = snapshot(
            listOf(leg("agent:X", "Legacy Agent")),
            listOf(owned("agent:X", "deleted", deletedAt = 9L))
        )
        val displayIds = s.displayRecords().map { it.id }
        assertTrue(
            "a deleted id must not resurface its shadowed legacy record, got $displayIds",
            displayIds.isEmpty()
        )
    }

    @Test
    fun display_keeps_legacy_records_which_have_no_tombstone() {
        val s = snapshot(listOf(leg("agent:1", "L1"), leg("agent:2", "L2")))
        assertEquals(listOf("agent:1", "agent:2"), s.displayRecords().map { it.id })
    }

    // ---- D1: legacy visible, never adopted; no cross-account exposure ----

    @Test
    fun never_signed_in_snapshot_is_legacy_only() {
        // The intent is a SIGNED-OUT snapshot, so the hash is passed explicitly:
        // with no verified identity the legacy store is DEVICE_LOCAL and runnable.
        val s = snapshot(listOf(leg("agent:1", "L1")), hash = null)
        assertEquals(1, s.records().size)
        assertTrue(s.records()[0] is AccountScope.VisibleAgent.Legacy)
        assertEquals("hint-L1", s.hint("agent:1"))
    }

    @Test
    fun snapshot_exposes_no_other_account_records() {
        val s = snapshot(account = listOf(owned("agent:A", "A-only")))
        assertTrue(s.isKnown("agent:A"))
        assertTrue(!s.isKnown("agent:B"))
        assertNull(s.resolve("agent:B"))
    }

    @Test
    fun a_to_b_to_a_each_snapshot_resolves_its_own_record() {
        val device = leg("agent:dev", "Device")
        val asA = snapshot(listOf(device), listOf(owned("agent:s", "A-agent")), hashA)
        val asB = snapshot(listOf(device), listOf(owned("agent:s", "B-agent")), hashB)
        val back = snapshot(listOf(device), listOf(owned("agent:s", "A-agent")), hashA)
        assertEquals("hint-A-agent", asA.hint("agent:s"))
        assertEquals("hint-B-agent", asB.hint("agent:s"))
        assertEquals("hint-A-agent", back.hint("agent:s"))
        // STAGE 6: the shared legacy device record stays LISTED in each snapshot
        // but is not executable under any signed-in account.
        listOf(asA, asB, back).forEach {
            assertTrue(it.displayRecords().any { r -> r.id == "agent:dev" })
            assertFalse(it.isKnown("agent:dev"))
        }
    }

    @Test
    fun collision_renders_the_account_record() {
        val s = snapshot(
            listOf(leg("agent:X", "Legacy Agent")),
            listOf(owned("agent:X", "Account Agent"))
        )
        assertEquals(1, s.records().size)
        assertEquals("Account Agent", s.records()[0].name)
        assertEquals("hint-Account Agent", s.hint("agent:X"))
    }

    @Test
    fun empty_snapshot_still_resolves_builtin() {
        val s = snapshot()
        assertNull(s.resolve("agent:1"))
        assertNull(s.resolve(null))
        assertTrue(!s.isKnown("agent:1"))
    }

    @Test
    fun signed_out_hash_yields_legacy_only_through_the_real_loader() {
        // Exercises the production loader, not just the union: a null hash must
        // open no account store and leave device-local data intact.
        val store = FakePrefsStore()
        val ctx = store.context()
        AccountScope.saveAgents(ctx, hashA, listOf(owned("agent:A", "A-only")))
        val union = AccountScope.loadVisibleAgents(
            ctx, null, listOf(leg("agent:dev", "Device"))
        )
        assertEquals(listOf("agent:dev"), union.map { it.id })
    }

    @Test
    fun a_signed_in_hash_adds_account_records_alongside_legacy() {
        val store = FakePrefsStore()
        val ctx = store.context()
        AccountScope.saveAgents(ctx, hashA, listOf(owned("agent:A", "A-only")))
        val union = AccountScope.loadVisibleAgents(
            ctx, hashA, listOf(leg("agent:dev", "Device"))
        )
        assertEquals(listOf("agent:A", "agent:dev"), union.map { it.id })
    }

    @Test
    fun another_accounts_store_is_not_merged_in() {
        val store = FakePrefsStore()
        val ctx = store.context()
        AccountScope.saveAgents(ctx, hashA, listOf(owned("agent:A", "A-only")))
        AccountScope.saveAgents(ctx, hashB, listOf(owned("agent:B", "B-only")))
        val union = AccountScope.loadVisibleAgents(
            ctx, hashA, listOf(leg("agent:dev", "Device"))
        )
        val ids = union.map { it.id }
        assertTrue(ids.contains("agent:A"))
        assertTrue("B's record must not appear for A", !ids.contains("agent:B"))
    }
}
