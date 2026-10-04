package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 wiring — resolution over the union, Android mirror of the Windows
 * `is_known_id` / `resolve_active_agent` tests.
 *
 * Confirmed design: the UI lists the union, and the existing
 * `resolveActiveAgent` / `isKnownAgent` resolution logic operates on that same
 * union. There is deliberately **no second account-specific selection
 * mechanism** — `default_id` and `package_overrides` remain device-local and are
 * not read or written here.
 */
class AccountScopeWiringTest {

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

    private fun names(u: List<AccountScope.VisibleAgent>) = u.map { it.name }

    // 1/2/3 — legacy-only, account-only, both

    @Test
    fun is_known_legacy_only() {
        val u = AccountScope.unionAgents(listOf(leg("agent:1", "L1")), emptyList())
        assertTrue(AccountScope.isKnownAgentIn(u, AccountScope.LEGACY_BUILT_IN_ID))
        assertTrue(AccountScope.isKnownAgentIn(u, "agent:1"))
        assertTrue(!AccountScope.isKnownAgentIn(u, "agent:2"))
    }

    @Test
    fun is_known_account_only() {
        val u = AccountScope.unionAgents(emptyList(), listOf(owned("agent:A", "A1")))
        assertTrue(AccountScope.isKnownAgentIn(u, "agent:A"))
        assertTrue(!AccountScope.isKnownAgentIn(u, "agent:1"))
    }

    @Test
    fun is_known_both() {
        val u = AccountScope.unionAgents(listOf(leg("agent:1", "L1")), listOf(owned("agent:2", "A2")))
        assertTrue(AccountScope.isKnownAgentIn(u, "agent:1"))
        assertTrue(AccountScope.isKnownAgentIn(u, "agent:2"))
    }

    // 4 — same-id collision: account record wins, and resolution uses it

    @Test
    fun collision_account_record_wins_for_display_and_resolution() {
        val u = AccountScope.unionAgents(
            listOf(leg("agent:X", "Legacy Agent")),
            listOf(owned("agent:X", "Account Agent"))
        )
        assertEquals(1, u.size)
        assertEquals("Account Agent", u[0].name)
        assertTrue(AccountScope.isKnownAgentIn(u, "agent:X"))
        val resolved = AccountScope.resolveAgentIn(u, "agent:X")
        assertEquals("Account Agent", resolved?.name)
        assertTrue(resolved is AccountScope.VisibleAgent.Owned)
    }

    // 5 — A → B → A

    @Test
    fun a_to_b_to_a_account_switching() {
        val deviceRecord = leg("agent:dev", "Device Agent")
        val asA = AccountScope.unionAgents(listOf(deviceRecord), listOf(owned("agent:s", "A-agent")))
        val asB = AccountScope.unionAgents(listOf(deviceRecord), listOf(owned("agent:s", "B-agent")))
        val backToA = AccountScope.unionAgents(listOf(deviceRecord), listOf(owned("agent:s", "A-agent")))

        assertEquals("A-agent", AccountScope.resolveAgentIn(asA, "agent:s")?.name)
        assertEquals("B-agent", AccountScope.resolveAgentIn(asB, "agent:s")?.name)
        // Returning to A yields A's record, never B's.
        assertEquals("A-agent", AccountScope.resolveAgentIn(backToA, "agent:s")?.name)
        // The device-local record is visible throughout and never displaced.
        listOf(asA, asB, backToA).forEach {
            assertTrue(AccountScope.isKnownAgentIn(it, "agent:dev"))
        }
    }

    // 6 — never signed in

    @Test
    fun never_signed_in_sees_legacy_only() {
        val u = AccountScope.unionAgents(listOf(leg("agent:1", "L1")), emptyList())
        assertEquals(listOf("L1"), names(u))
        assertEquals("L1", AccountScope.resolveAgentIn(u, "agent:1")?.name)
    }

    // 7 — no visibility of another account's records

    @Test
    fun another_accounts_records_are_not_visible() {
        val u = AccountScope.unionAgents(listOf(leg("agent:dev", "D")), listOf(owned("agent:A", "A-only")))
        assertTrue(AccountScope.isKnownAgentIn(u, "agent:A"))
        // Nothing from an account we did not pass in.
        assertTrue(!AccountScope.isKnownAgentIn(u, "agent:B"))
        assertNull(AccountScope.resolveAgentIn(u, "agent:B"))
    }

    // 8/9 — resolveActiveAgent / isKnownAgent over the union

    @Test
    fun resolve_active_agent_over_union() {
        val u = AccountScope.unionAgents(listOf(leg("agent:1", "L1")), listOf(owned("agent:2", "A2")))
        assertEquals("L1", AccountScope.resolveAgentIn(u, "agent:1")?.name)
        assertEquals("A2", AccountScope.resolveAgentIn(u, "agent:2")?.name)
    }

    @Test
    fun resolve_active_agent_builtin_and_unknown_fall_back_to_builtin() {
        val u = AccountScope.unionAgents(listOf(leg("agent:1", "L1")), emptyList())
        // Built-in is represented by a null hint, exactly as the existing
        // resolveActiveAgent does; an unknown id resolves to nothing, which the
        // caller maps to built-in.
        assertNull(AccountScope.resolveAgentIn(u, AccountScope.LEGACY_BUILT_IN_ID))
        assertNull(AccountScope.resolveAgentIn(u, "agent:does-not-exist"))
        assertNull(AccountScope.resolveAgentIn(u, null))
    }

    @Test
    fun tombstoned_account_record_still_resolves_by_id() {
        // A delete must propagate, not silently fall back to a shadowed legacy
        // record, so the tombstoned record is what resolution finds.
        val u = AccountScope.unionAgents(
            listOf(leg("agent:X", "Legacy Agent")),
            listOf(owned("agent:X", "deleted", deletedAt = 42L))
        )
        val resolved = AccountScope.resolveAgentIn(u, "agent:X")
        assertTrue(resolved is AccountScope.VisibleAgent.Owned)
        assertEquals(42L, resolved?.deletedAt)
    }

    // 12 — missing/corrupt account store must not destroy valid legacy data

    @Test
    fun missing_account_store_keeps_legacy_readable() {
        // No account store at all (signed out or not yet created).
        val u = AccountScope.unionAgents(listOf(leg("agent:1", "L1"), leg("agent:2", "L2")), emptyList())
        assertEquals(listOf("L1", "L2"), names(u))
        assertTrue(AccountScope.isKnownAgentIn(u, "agent:1"))
    }

    // 13 — display-after-merge consistency

    @Test
    fun display_and_resolution_agree_on_the_account_records_copy() {
        // The real property, deliberately NOT named for LWW: the union displays
        // whatever the account store holds, and resolution reads the same object,
        // so once the 2B merge normalises a duplicate id and writes the winner
        // back, both agree on that winner. No merge is simulated here and no
        // merge rule is invented — the test pins display/resolution agreement
        // with no separate resolution path.
        val stored = owned("agent:dup", "stored-winner")
        val u = AccountScope.unionAgents(listOf(leg("agent:dev", "D")), listOf(stored))
        assertEquals("stored-winner", AccountScope.resolveAgentIn(u, "agent:dup")?.name)
        assertEquals("stored-winner", u.first { it.id == "agent:dup" }.name)
    }

    // 10/11 — device-local selections are untouched by this module

    @Test
    fun module_does_not_read_or_write_default_id_or_overrides() {
        // Structural assertion: the union and resolution helpers take no
        // selection state, so device-local `default_id` / `package_overrides`
        // cannot become account-scoped through this path.
        val u = AccountScope.unionAgents(listOf(leg("agent:1", "L1")), listOf(owned("agent:2", "A2")))
        assertEquals(2, u.size)
        // Resolution is by explicit id only; nothing falls back to a selection.
        assertNull(AccountScope.resolveAgentIn(u, null))
    }
}
