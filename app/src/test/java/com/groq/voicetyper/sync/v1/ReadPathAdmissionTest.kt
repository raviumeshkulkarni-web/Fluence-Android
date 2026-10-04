package com.groq.voicetyper.sync.v1

import com.groq.voicetyper.sync.v1.AccountScope.Admission
import com.groq.voicetyper.sync.v1.AccountScope.VisibleAgent
import com.groq.voicetyper.sync.v1.AccountScope.isAdmissible
import com.groq.voicetyper.sync.v1.AccountScope.isDisplayable
import com.groq.voicetyper.sync.v1.AccountScope.isSyncable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 STAGE 6 — production read-path wiring.
 *
 * The architecture under test:
 *
 *     RAW RECORDS -> ADMISSION FILTER -> ADMITTED SNAPSHOT -> RUNTIME CONSUMERS
 *
 * `VisibleAgentsSnapshot` is the single object every runtime consumer resolves
 * through, so these tests pin the *snapshot's* behaviour rather than each
 * screen's. The screen-level wiring was made mechanical by funnelling
 * `AgentPreferences.loadCustomAgents` / `isKnownAgent` / `resolveActiveAgent`
 * through the same gate.
 *
 * The headline property: **display, selection and execution resolve the same
 * record.** If those could disagree, a user could be shown an agent that
 * selection accepts and execution silently refuses.
 */
class ReadPathAdmissionTest {

    private val hashA = "a".repeat(64)

    private fun owned(id: String, name: String = "Owned$id", deletedAt: Long? = null) =
        AccountScope.AccountAgent(
            id = id, name = name, hint = "hint-$id",
            syncId = "sync-$id", updatedAt = 1000L, deviceId = "dev-1", deletedAt = deletedAt,
        )

    private fun legacy(id: String, name: String = "Legacy$id") =
        VisibleAgent.Legacy(id = id, name = name, hint = "hint-$id")

    private fun snapshot(
        legacy: List<VisibleAgent.Legacy> = emptyList(),
        account: List<AccountScope.AccountAgent> = emptyList(),
        hash: String? = hashA,
    ) = AccountScope.VisibleAgentsSnapshot.forTest(
        AccountScope.unionAgents(legacy, account), hash
    )

    // ------------------------------------------------------------------
    // Nothing reaches a consumer without passing admission
    // ------------------------------------------------------------------

    @Test
    fun an_unassigned_record_never_reaches_execution() {
        val snap = snapshot(legacy = listOf(legacy("l1")))
        assertNull("must not resolve to a hint", snap.resolve("l1"))
        assertNull(snap.hint("l1"))
        assertFalse("must not be known-executable", snap.isKnown("l1"))
        assertTrue(snap.admitted().none { it.id == "l1" })
    }

    @Test
    fun an_unassigned_record_never_reaches_the_llm_prompt() {
        // The hint IS the system prompt, so this is the assertion that matters
        // most for cross-account isolation.
        val snap = snapshot(legacy = listOf(legacy("l1", "LeakedPrompt")))
        assertNull(snap.hint("l1"))
    }

    @Test
    fun owned_records_resolve_normally() {
        val snap = snapshot(account = listOf(owned("x1", "MyAgent")))
        assertEquals("MyAgent", snap.resolve("x1")?.name)
        assertEquals("hint-x1", snap.hint("x1"))
        assertTrue(snap.isKnown("x1"))
    }

    // ------------------------------------------------------------------
    // Display vs execution: separated on purpose
    // ------------------------------------------------------------------

    @Test
    fun an_unassigned_record_is_still_listed_so_it_does_not_look_deleted() {
        val snap = snapshot(legacy = listOf(legacy("l1", "MyOldAgent")))
        assertEquals(
            "hiding it would make intact data look destroyed",
            listOf("l1"),
            snap.displayRecords().map { it.id },
        )
        assertEquals(Admission.UNASSIGNED, snap.admissionOf(snap.displayRecords().single()))
    }

    @Test
    fun a_listed_unassigned_record_is_marked_not_runnable() {
        val snap = snapshot(legacy = listOf(legacy("l1")))
        val shown = snap.displayRecords().single()
        assertFalse("listed but not runnable", snap.isRunnable(shown))
    }

    @Test
    fun a_listed_owned_record_is_runnable() {
        val snap = snapshot(account = listOf(owned("x1")))
        val shown = snap.displayRecords().single()
        assertTrue(snap.isRunnable(shown))
        assertEquals(Admission.OWNED, snap.admissionOf(shown))
    }

    // ------------------------------------------------------------------
    // Tombstones
    // ------------------------------------------------------------------

    @Test
    fun a_tombstone_is_neither_listed_nor_runnable() {
        val snap = snapshot(account = listOf(owned("x1", deletedAt = 5000L)))
        assertTrue("must not render as a listed agent", snap.displayRecords().none { it.id == "x1" })
        assertNull("must not resolve to a hint", snap.resolve("x1"))
        val tombstone = VisibleAgent.Owned("x1", "OwnedVersion", "hint-x1", "sync-x1", 1000L, "dev-1", 5000L)
        assertFalse(snap.isRunnable(tombstone))
    }

    @Test
    fun a_tombstone_still_survives_for_sync() {
        // Removing it from `records()` would let a stale copy resurrect it.
        val snap = snapshot(account = listOf(owned("x1", deletedAt = 5000L)))
        assertEquals(listOf("x1"), snap.records().map { it.id })
    }

    // ------------------------------------------------------------------
    // The headline invariant
    // ------------------------------------------------------------------

    @Test
    fun display_selection_and_execution_resolve_the_same_record() {
        // For every listed record: if the UI shows it as runnable, selection must
        // accept it AND execution must resolve it — and vice versa. Divergence
        // here is the bug that makes a user pick an agent that then does nothing.
        val snap = snapshot(
            legacy = listOf(legacy("l1"), legacy("l2")),
            account = listOf(owned("x1"), owned("x2", deletedAt = 5000L)),
        )
        snap.displayRecords().forEach { shown ->
            val runnable = snap.isRunnable(shown)
            assertEquals(
                "display/execution disagree for ${shown.id}",
                runnable,
                snap.resolve(shown.id) != null,
            )
            assertEquals(
                "selection/execution disagree for ${shown.id}",
                runnable,
                snap.isKnown(shown.id),
            )
        }
    }

    @Test
    fun a_default_id_cannot_resolve_to_an_inadmissible_record() {
        val snap = snapshot(legacy = listOf(legacy("l1")))
        assertNull("default pointing at legacy must not resolve", snap.resolve("l1"))
    }

    // ------------------------------------------------------------------
    // Fallback safety
    // ------------------------------------------------------------------

    @Test
    fun unknown_and_builtin_ids_resolve_to_null_without_erroring() {
        val snap = snapshot(account = listOf(owned("x1")))
        listOf(null, "", "   ", "nope", "builtin").forEach {
            assertNull("id=$it", snap.resolve(it))
        }
    }

    @Test
    fun a_shadowed_legacy_record_never_falls_through_to_execution() {
        // A tombstoned owned record wins its id in the union. If the union were
        // filtered to admitted records BEFORE resolution, the shadowed legacy
        // record would become reachable — resurrecting a deleted agent.
        val snap = snapshot(
            legacy = listOf(legacy("dup", "ShadowedLegacy")),
            account = listOf(owned("dup", "OwnedVersion", deletedAt = 5000L)),
        )
        assertNull("a deleted agent must not fall through to a shadowed legacy record", snap.resolve("dup"))
    }

    // ------------------------------------------------------------------
    // Signed-out behaviour
    // ------------------------------------------------------------------

    @Test
    fun a_signed_out_device_runs_legacy_records() {
        val snap = snapshot(legacy = listOf(legacy("l1", "OfflineAgent")), hash = null)
        assertEquals("OfflineAgent", snap.resolve("l1")?.name)
        assertTrue(snap.isKnown("l1"))
        assertEquals(Admission.DEVICE_LOCAL, snap.admissionOf(snap.displayRecords().single()))
    }

    @Test
    fun a_signed_out_device_does_not_see_another_accounts_records() {
        // The union only ever contains the ACTIVE account's file plus legacy, so
        // signed-out means legacy only.
        val snap = snapshot(legacy = listOf(legacy("l1")), account = listOf(owned("x1")), hash = null)
        assertTrue("account records must not surface while signed out", snap.displayRecords().none { it.id == "x1" })
    }

    // ------------------------------------------------------------------
    // Admission predicates stay honest
    // ------------------------------------------------------------------

    @Test
    fun displayable_and_admissible_are_deliberately_different() {
        // Guards the footgun: a consumer that checks only isAdmissible() would
        // hide unassigned records and reintroduce the "my agents vanished" bug.
        assertTrue(Admission.UNASSIGNED.isDisplayable())
        assertFalse(Admission.UNASSIGNED.isAdmissible())
    }

    @Test
    fun unassigned_is_still_never_syncable() {
        // The display relaxation must not have leaked into upload.
        assertFalse(Admission.UNASSIGNED.isSyncable())
        assertFalse(Admission.DEVICE_LOCAL.isSyncable())
        assertTrue(Admission.OWNED.isSyncable())
    }

    @Test
    fun the_snapshot_exposes_an_admitted_projection() {
        val snap = snapshot(legacy = listOf(legacy("l1")), account = listOf(owned("x1")))
        assertEquals(listOf("x1"), snap.admitted().map { it.id })
        assertNotNull(snap.admitted().firstOrNull())
    }
}
