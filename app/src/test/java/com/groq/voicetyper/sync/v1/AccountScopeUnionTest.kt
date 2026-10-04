package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 read-union — Android mirror of the Windows `union_agents` /
 * `union_styles` tests, so both platforms decide the same thing.
 *
 * Decided rule: **account wins on id collision**, and a shadowed legacy record is
 * never deleted, mutated, or adopted.
 */
class AccountScopeUnionTest {

    private fun acct(id: String, name: String, deletedAt: Long? = null) =
        AccountScope.AccountAgent(
            id = id, name = name, hint = "hint-$name",
            syncId = "sync-$id", updatedAt = 100L, deviceId = "dev-a",
            deletedAt = deletedAt
        )

    private fun acctStyle(id: String, name: String, deletedAt: Long? = null) =
        AccountScope.AccountStyle(
            id = id, name = name, hint = "hint-$name",
            syncId = "sync-$id", updatedAt = 100L, deviceId = "dev-a",
            deletedAt = deletedAt
        )

    private fun legacy(id: String, name: String) =
        AccountScope.VisibleAgent.Legacy(id = id, name = name, hint = "hint-$name")

    private fun legacyStyle(id: String, name: String) =
        AccountScope.VisibleStyle.Legacy(id = id, name = name, hint = "hint-$name")

    private fun names(v: List<AccountScope.VisibleAgent>) = v.map { it.name }

    // ---- the decided cases -----------------------------------------------

    @Test
    fun legacy_only() {
        val out = AccountScope.unionAgents(listOf(legacy("agent:1", "L1")), emptyList())
        assertEquals(listOf("L1"), names(out))
        assertTrue(out[0] is AccountScope.VisibleAgent.Legacy)
    }

    @Test
    fun account_only() {
        val out = AccountScope.unionAgents(emptyList(), listOf(acct("agent:1", "A1")))
        assertEquals(listOf("A1"), names(out))
        assertTrue(out[0] is AccountScope.VisibleAgent.Owned)
    }

    @Test
    fun both_with_distinct_ids() {
        val out = AccountScope.unionAgents(
            listOf(legacy("agent:1", "L1")),
            listOf(acct("agent:2", "A2"))
        )
        assertEquals(listOf("A2", "L1"), names(out))
    }

    @Test
    fun same_id_account_wins() {
        val out = AccountScope.unionAgents(
            listOf(legacy("agent:X", "Legacy Agent")),
            listOf(acct("agent:X", "Account Agent"))
        )
        assertEquals(listOf("Account Agent"), names(out))
        assertEquals(1, out.size)
        assertTrue(out[0] is AccountScope.VisibleAgent.Owned)
    }

    @Test
    fun shadowed_legacy_is_not_mutated_or_adopted() {
        val legacyList = listOf(legacy("agent:X", "Legacy Agent"))
        val snapshot = legacyList.toList()
        AccountScope.unionAgents(legacyList, listOf(acct("agent:X", "Account Agent")))
        assertEquals("union must not mutate its input", snapshot, legacyList)
    }

    @Test
    fun shadowed_legacy_returns_when_no_account_signed_in() {
        val legacyList = listOf(legacy("agent:X", "Legacy Agent"))
        val signedIn = AccountScope.unionAgents(
            legacyList, listOf(acct("agent:X", "Account Agent"))
        )
        assertEquals(listOf("Account Agent"), names(signedIn))
        val signedOut = AccountScope.unionAgents(legacyList, emptyList())
        assertEquals(listOf("Legacy Agent"), names(signedOut))
    }

    @Test
    fun multiple_account_records() {
        val out = AccountScope.unionAgents(
            emptyList(),
            listOf(acct("agent:1", "A1"), acct("agent:2", "A2"), acct("agent:3", "A3"))
        )
        assertEquals(listOf("A1", "A2", "A3"), names(out))
    }

    @Test
    fun empty_and_missing_account_store() {
        // No records at all -> nothing.
        assertEquals(emptyList<AccountScope.VisibleAgent>(), AccountScope.unionAgents(emptyList(), emptyList()))
        // A missing/empty ACCOUNT store must NOT hide device-local records: that
        // is D1a. Legacy remains fully visible.
        val legacyOnly = AccountScope.unionAgents(listOf(legacy("agent:1", "L1")), emptyList())
        assertEquals(listOf("L1"), names(legacyOnly))
        assertTrue(legacyOnly[0] is AccountScope.VisibleAgent.Legacy)
    }

    @Test
    fun corrupt_account_store_yields_legacy_only_and_fabricates_nothing() {
        // A rejected/corrupt account store contributes nothing. Legacy stays
        // visible, nothing is invented, nothing is overwritten.
        val out = AccountScope.unionAgents(
            listOf(legacy("agent:1", "L1"), legacy("agent:2", "L2")),
            emptyList()
        )
        assertEquals(listOf("L1", "L2"), names(out))
    }

    @Test
    fun tombstoned_account_record_is_preserved() {
        val out = AccountScope.unionAgents(
            listOf(legacy("agent:1", "L1")),
            listOf(acct("agent:2", "gone", deletedAt = 1_700_000_000_000L))
        )
        assertEquals(2, out.size)
        val owned = out[0] as AccountScope.VisibleAgent.Owned
        assertEquals(1_700_000_000_000L, owned.deletedAt)
    }

    @Test
    fun tombstone_shadows_legacy_even_when_deleted() {
        val legacyList = listOf(legacy("agent:X", "Legacy Agent"))
        val out = AccountScope.unionAgents(
            legacyList, listOf(acct("agent:X", "deleted", deletedAt = 42L))
        )
        assertEquals(1, out.size)
        assertEquals(42L, (out[0] as AccountScope.VisibleAgent.Owned).deletedAt)
        assertEquals(listOf("Legacy Agent"), names(AccountScope.unionAgents(legacyList, emptyList())))
    }

    @Test
    fun deduplicates_within_account_records() {
        val out = AccountScope.unionAgents(
            emptyList(), listOf(acct("agent:1", "first"), acct("agent:1", "second"))
        )
        assertEquals(1, out.size)
        assertEquals(listOf("first"), names(out))
    }

    @Test
    fun is_deterministic() {
        val legacyList = listOf(legacy("agent:1", "L1"), legacy("agent:X", "LX"))
        val acctList = listOf(acct("agent:2", "A2"), acct("agent:X", "AX"))
        val once = AccountScope.unionAgents(legacyList, acctList)
        assertEquals(once, AccountScope.unionAgents(legacyList, acctList))
    }

    @Test
    fun fabricates_nothing() {
        val legacyList = listOf(legacy("agent:1", "L1"))
        val acctList = listOf(acct("agent:2", "A2"))
        val out = AccountScope.unionAgents(legacyList, acctList)
        assertEquals(legacyList.size + acctList.size, out.size)
        val inputIds = (legacyList.map { it.id } + acctList.map { it.id }).toSet()
        out.forEach { assertTrue("union invented id ${it.id}", inputIds.contains(it.id)) }
    }

    // ---- styles mirror the same rules -------------------------------------

    @Test
    fun style_same_id_account_wins() {
        val out = AccountScope.unionStyles(
            listOf(legacyStyle("custom:X", "Legacy Style")),
            listOf(acctStyle("custom:X", "Account Style"))
        )
        assertEquals(1, out.size)
        assertEquals("Account Style", (out[0] as AccountScope.VisibleStyle.Owned).name)
    }

    @Test
    fun style_union_both_and_empty() {
        val out = AccountScope.unionStyles(
            listOf(legacyStyle("custom:1", "L1")), listOf(acctStyle("custom:2", "A2"))
        )
        assertEquals(2, out.size)
        assertEquals(emptyList<AccountScope.VisibleStyle>(), AccountScope.unionStyles(emptyList(), emptyList()))
    }

    @Test
    fun style_union_preserves_tombstone() {
        val out = AccountScope.unionStyles(emptyList(), listOf(acctStyle("custom:1", "gone", deletedAt = 7L)))
        assertEquals(7L, (out[0] as AccountScope.VisibleStyle.Owned).deletedAt)
    }
}
