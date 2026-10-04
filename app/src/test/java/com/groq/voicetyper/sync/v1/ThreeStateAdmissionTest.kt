package com.groq.voicetyper.sync.v1

import com.groq.voicetyper.sync.v1.AccountScope.Admission
import com.groq.voicetyper.sync.v1.AccountScope.VisibleAgent
import com.groq.voicetyper.sync.v1.AccountScope.VisibleStyle
import com.groq.voicetyper.sync.v1.AccountScope.admittedAgents
import com.groq.voicetyper.sync.v1.AccountScope.admittedStyles
import com.groq.voicetyper.sync.v1.AccountScope.admitAgent
import com.groq.voicetyper.sync.v1.AccountScope.admitStyle
import com.groq.voicetyper.sync.v1.AccountScope.isAdmissible
import com.groq.voicetyper.sync.v1.AccountScope.isSyncable
import com.groq.voicetyper.sync.v1.AccountScope.unionAgents
import com.groq.voicetyper.sync.v1.AccountScope.unionStyles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 STAGE 5 — three-state local admission.
 *
 * Contract under test:
 *
 *  - OWNED        visible, selectable, executable, SYNCABLE
 *  - DEVICE_LOCAL visible, executable on this device, NEVER auto-uploaded,
 *                 never silently reassigned
 *  - UNASSIGNED   NOT executable while an account is signed in, NOT uploaded,
 *                 preserved intact, becomes OWNED only by explicit assignment
 *
 * The property that matters most: classification is derived from **where the
 * record physically lives**, never from who happens to be signed in. Signing in
 * must not be capable of promoting an unknown-provenance record to OWNED.
 */
class ThreeStateAdmissionTest {

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    private fun owned(
        id: String,
        name: String = "Owned$id",
        hint: String = "hint-$id",
        syncId: String? = "sync-$id",
        updatedAt: Long? = 1000L,
        deletedAt: Long? = null,
    ) = VisibleAgent.Owned(
        id = id, name = name, hint = hint,
        syncId = syncId, updatedAt = updatedAt, deviceId = "dev-1", deletedAt = deletedAt,
    )

    private fun legacy(id: String, name: String = "Legacy$id") =
        VisibleAgent.Legacy(id = id, name = name, hint = "hint-$id")

    /**
     * A raw account-store record, as persisted in `agents.account-<hash>.json`.
     * This — not [owned] — is what the read-union is built from.
     */
    private fun acctAgent(
        id: String,
        name: String = "Owned$id",
        syncId: String? = "sync-$id",
        updatedAt: Long? = 1000L,
    ) = AccountScope.AccountAgent(
        id = id, name = name, hint = "hint-$id",
        syncId = syncId, updatedAt = updatedAt, deviceId = "dev-1", deletedAt = null,
    )

    private fun acctStyle(id: String, name: String = "S$id") =
        AccountScope.AccountStyle(
            id = id, name = name, hint = "h",
            syncId = "sync-$id", updatedAt = 1000L, deviceId = "dev-1", deletedAt = null,
        )

    private fun ownedStyle(id: String) =
        VisibleStyle.Owned(
            id = id, name = "S$id", hint = "h",
            syncId = "sync-$id", updatedAt = 1000L, deviceId = "dev-1", deletedAt = null,
        )

    private fun legacyStyle(id: String) =
        VisibleStyle.Legacy(id = id, name = "LS$id", hint = "h")

    // ------------------------------------------------------------------
    // Classification
    // ------------------------------------------------------------------

    @Test
    fun an_account_file_record_is_owned() {
        assertEquals(Admission.OWNED, admitAgent(owned("x1"), hashA))
        assertEquals(Admission.OWNED, admitStyle(ownedStyle("x1"), hashA))
    }

    @Test
    fun a_legacy_store_record_is_unassigned_while_signed_in() {
        assertEquals(Admission.UNASSIGNED, admitAgent(legacy("l1"), hashA))
        assertEquals(Admission.UNASSIGNED, admitStyle(legacyStyle("l1"), hashA))
    }

    @Test
    fun classification_does_not_depend_on_which_account_is_signed_in() {
        // The point of deriving provenance from storage location: no account can
        // be chosen that would reclassify the record.
        listOf(hashA, hashB).forEach { h ->
            assertEquals(Admission.OWNED, admitAgent(owned("x1"), h))
            assertEquals(Admission.UNASSIGNED, admitAgent(legacy("l1"), h))
        }
    }

    @Test
    fun a_signed_out_device_withholds_nothing() {
        // Only a POSITIVELY absent account counts as signed out. There is no
        // identity to be confused with, so withholding would only brick the app.
        listOf(null, "", "   ", "\t\n").forEach { h ->
            assertEquals("$h", Admission.DEVICE_LOCAL, admitAgent(owned("x1"), h))
            assertEquals("$h", Admission.DEVICE_LOCAL, admitAgent(legacy("l1"), h))
        }
    }

    // ------------------------------------------------------------------
    // Executability / visibility
    // ------------------------------------------------------------------

    @Test
    fun unassigned_is_not_admissible_while_signed_in() {
        assertFalse(Admission.UNASSIGNED.isAdmissible())
        assertTrue(Admission.OWNED.isAdmissible())
        assertTrue(Admission.DEVICE_LOCAL.isAdmissible())
    }

    @Test
    fun unassigned_records_are_withheld_from_the_admitted_snapshot() {
        val union = unionAgents(
            legacy = listOf(legacy("l1"), legacy("l2")),
            account = listOf(acctAgent("x1"), acctAgent("x2")),
        )
        val admitted = admittedAgents(union, hashA)
        assertEquals(listOf("x1", "x2"), admitted.map { it.id })
    }

    @Test
    fun unassigned_styles_are_withheld_too() {
        val union = unionStyles(
            legacy = listOf(legacyStyle("l1")),
            account = listOf(acctStyle("x1")),
        )
        assertEquals(listOf("x1"), admittedStyles(union, hashA).map { it.id })
    }

    @Test
    fun everything_is_admissible_when_signed_out() {
        val union = unionAgents(
            legacy = listOf(legacy("l1")),
            account = listOf(acctAgent("x1")),
        )
        assertEquals(2, admittedAgents(union, null).size)
    }

    // ------------------------------------------------------------------
    // Sync filtering — the rule that protects other accounts
    // ------------------------------------------------------------------

    @Test
    fun only_owned_records_are_syncable() {
        assertTrue(Admission.OWNED.isSyncable())
        assertFalse("DEVICE_LOCAL must never be auto-uploaded", Admission.DEVICE_LOCAL.isSyncable())
        assertFalse("UNASSIGNED must never be uploaded", Admission.UNASSIGNED.isSyncable())
    }

    @Test
    fun an_unassigned_record_never_becomes_syncable_just_by_signing_in() {
        // The exact failure STAGE 5 exists to prevent: signing in silently
        // promoting an unknown-provenance agent into the account's Drive
        // partition, where another device would then adopt and run it.
        val before = admitAgent(legacy("l1"), null)
        val after = admitAgent(legacy("l1"), hashA)
        assertFalse(before.isSyncable())
        assertFalse(after.isSyncable())
        assertEquals(Admission.UNASSIGNED, after)
    }

    // ------------------------------------------------------------------
    // Preservation
    // ------------------------------------------------------------------

    @Test
    fun admission_never_mutates_or_drops_the_underlying_record() {
        val original = legacy("l1", "KeepMe")
        val union = unionAgents(listOf(original), emptyList())
        val admitted = admittedAgents(union, hashA)
        assertTrue("withheld from the snapshot", admitted.none { it.id == "l1" })
        assertEquals("the record itself must be untouched", "KeepMe", union.single { it.id == "l1" }.name)
    }

    @Test
    fun an_unassigned_record_returns_when_the_account_signs_out() {
        val union = unionAgents(listOf(legacy("l1")), listOf(acctAgent("x1")))
        assertEquals(listOf("x1"), admittedAgents(union, hashA).map { it.id })
        assertEquals(
            "signing out must not destroy or hide it permanently",
            setOf("x1", "l1"),
            admittedAgents(union, null).map { it.id }.toSet(),
        )
    }

    // ------------------------------------------------------------------
    // DEVICE_LOCAL rules, pinned even though nothing produces it today
    // ------------------------------------------------------------------

    @Test
    fun device_local_is_usable_but_never_uploaded() {
        assertTrue(Admission.DEVICE_LOCAL.isAdmissible())
        assertFalse(Admission.DEVICE_LOCAL.isSyncable())
    }

    @Test
    fun device_local_is_reachable_when_signed_out() {
        // The only way a record is classified DEVICE_LOCAL today.
        assertEquals(Admission.DEVICE_LOCAL, admitAgent(legacy("l1"), null))
    }

    // ------------------------------------------------------------------
    // Shadowing and precedence still hold under admission
    // ------------------------------------------------------------------

    @Test
    fun an_owned_record_shadows_a_legacy_record_of_the_same_id() {
        // Precedence must be decided BEFORE admission, otherwise a withheld
        // legacy record could shadow an owned one.
        val union = unionAgents(
            legacy = listOf(legacy("dup", "LegacyVersion")),
            account = listOf(acctAgent("dup", "OwnedVersion")),
        )
        val admitted = admittedAgents(union, hashA)
        assertEquals(1, admitted.size)
        assertEquals("OwnedVersion", admitted.single().name)
    }

    @Test
    fun account_records_come_first_and_legacy_is_deduplicated_by_id() {
        val union = unionAgents(
            legacy = listOf(legacy("a"), legacy("b")),
            account = listOf(acctAgent("a"), acctAgent("c")),
        )
        assertEquals(listOf("a", "c", "b"), union.map { it.id })
    }

    // ------------------------------------------------------------------
    // Tombstones
    // ------------------------------------------------------------------

    @Test
    fun a_tombstoned_owned_record_is_still_owned_and_admissible() {
        // Deletion is a sync concern, not an admission one: a tombstone must
        // still be visible to the UI as a deleted row and must still upload, or
        // the delete could never propagate.
        val rec = owned("x1", deletedAt = 5000L)
        assertEquals(Admission.OWNED, admitAgent(rec, hashA))
        assertTrue(admitAgent(rec, hashA).isSyncable())
    }

    // ------------------------------------------------------------------
    // Fail-closed
    // ------------------------------------------------------------------

    @Test
    fun a_malformed_account_hash_does_not_unlock_unassigned_records() {
        // If a bad hash were treated as "signed out", unassigned records would
        // become runnable. The gate must be strict in the safe direction:
        // a non-blank invalid hash is an UNVERIFIABLE identity, not an absent one.
        listOf("not-a-hash", hashA.take(63), hashA + "0", "g" + hashA.drop(1), hashA.uppercase())
            .forEach { h ->
                assertEquals(h, Admission.UNASSIGNED, admitAgent(legacy("l1"), h))
                assertFalse(admitAgent(legacy("l1"), h).isAdmissible())
            }
    }

    @Test
    fun a_malformed_account_hash_still_admits_records_from_an_account_store() {
        // The counterpart, so fail-closed does not over-reach: a record already
        // inside a hash-partitioned account file was written under a validated
        // hash, so a transient bad value must not lock the owner out of their
        // own data.
        listOf("not-a-hash", hashA.take(63), hashA.uppercase()).forEach { h ->
            assertEquals(h, Admission.OWNED, admitAgent(owned("x1"), h))
        }
    }
}
