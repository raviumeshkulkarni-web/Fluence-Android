package com.groq.voicetyper.agent

import com.groq.voicetyper.cleanup.AiCleanupPreferences
import com.groq.voicetyper.sync.SyncAccounts
import com.groq.voicetyper.sync.v1.AccountHash
import com.groq.voicetyper.sync.v1.AccountScope
import com.groq.voicetyper.sync.v1.FakePrefsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1 — a preserved legacy Agent/Style must stay VISIBLE while an account is
 * signed in, while remaining unselectable and unexecutable.
 *
 * The bug: `loadCustomAgents` / `loadCustomStyles` were the *execution* list, so
 * admission filtering also removed legacy records from the UI. The records were
 * never deleted and the legacy store is never written by the account module, so
 * the user saw their own custom system prompts disappear on sign-in and reappear
 * on sign-out — indistinguishable from data loss. Windows lists unassigned
 * records for exactly this reason.
 *
 * Every assertion here goes through a PRODUCTION entry point. The audit found
 * two existing tests that both passed while encoding opposite specifications,
 * because one exercised the display projection and the other the production
 * loader; testing the real loaders is what prevents that from recurring.
 */
class LegacyVisibilityTest {

    private val email = "test@example.com"
    private val hash = AccountHash.of(email)!!
    private val legacyAgentId = "agent:${"1".repeat(32)}"
    private val legacyStyleId = "custom:${"2".repeat(32)}"

    /** Signed in with an unverified identity — the window B1 is about. */
    private inline fun <T> signedIn(block: () -> T): T {
        val savedVerified = SyncAccounts.tokenVerified
        val savedAccount = SyncAccounts.cachedAccount
        SyncAccounts.clearAuthentication()
        SyncAccounts.cachedAccount = email
        try {
            return block()
        } finally {
            SyncAccounts.clearAuthentication()
            if (savedVerified) SyncAccounts.publishAuthenticated(savedAccount) else {
                SyncAccounts.cachedAccount = savedAccount
            }
        }
    }

    private fun store() = FakePrefsStore()

    private fun seedLegacyAgent(s: FakePrefsStore, id: String = legacyAgentId) {
        s.fileOf("fluence_prefs")["agent_custom_styles"] =
            """[{"id":"$id","name":"Legacy Agent","hint":"Old system prompt"}]"""
    }

    private fun seedLegacyStyle(s: FakePrefsStore, id: String = legacyStyleId) {
        s.fileOf("fluence_prefs")["ai_cleanup_custom_styles"] =
            """[{"id":"$id","name":"Legacy Style","hint":"Old cleanup prompt"}]"""
    }

    // ------------------------------------------------------------------
    // Visible, unchanged, and honestly labelled
    // ------------------------------------------------------------------

    @Test
    fun a_legacy_agent_stays_visible_while_signed_in() = signedIn {
        val s = store()
        seedLegacyAgent(s)

        val listed = AgentPreferences.loadCustomAgents(s.context())
        val legacy = listed.firstOrNull { it.id == legacyAgentId }
        assertNotNull("a preserved legacy agent must remain listed while signed in", legacy)
        assertEquals("Legacy Agent", legacy!!.name)
        assertEquals("Old system prompt", legacy.hint)
        assertFalse("it must not be runnable under a signed-in identity", legacy.available)
        assertEquals(
            "the reason shown to the user must be the agreed one",
            AgentPreferences.LEGACY_UNAVAILABLE_REASON,
            legacy.unavailableReason,
        )
    }

    @Test
    fun a_legacy_style_stays_visible_while_signed_in() = signedIn {
        val s = store()
        seedLegacyStyle(s)

        val legacy = AiCleanupPreferences.loadCustomStyles(s.context())
            .firstOrNull { it.id == legacyStyleId }
        assertNotNull("a preserved legacy style must remain listed while signed in", legacy)
        assertEquals("Legacy Style", legacy!!.name)
        assertEquals("Old cleanup prompt", legacy.hint)
        assertFalse(legacy.available)
        assertEquals(
            AiCleanupPreferences.LEGACY_UNAVAILABLE_REASON,
            legacy.unavailableReason,
        )
    }

    @Test
    fun reading_the_display_list_never_mutates_the_legacy_record() = signedIn {
        val s = store()
        seedLegacyAgent(s)
        val before = s.fileOf("fluence_prefs")["agent_custom_styles"]

        repeat(3) { AgentPreferences.loadCustomAgents(s.context()) }
        assertEquals("the legacy row must be byte-identical after reads", before, s.fileOf("fluence_prefs")["agent_custom_styles"])

        // And no account-owned row was fabricated from it: that would be an
        // implicit migration/assignment, which is forbidden.
        assertEquals(
            "no account-owned row may be created from a legacy record",
            0,
            AccountScope.loadAgents(s.context(), hash).size,
        )
    }

    // ------------------------------------------------------------------
    // Not selectable, not executable
    // ------------------------------------------------------------------

    @Test
    fun a_legacy_agent_is_never_executable() = signedIn {
        val s = store()
        seedLegacyAgent(s)

        assertFalse(AgentPreferences.isKnownAgent(s.context(), legacyAgentId))

        val resolved = AgentPreferences.resolveActiveAgent(s.context(), legacyAgentId)
        assertTrue(
            "an unresolvable id must fall back to the built-in agent",
            resolved.isBuiltIn,
        )
        assertNull("a legacy agent must never supply a prompt", resolved.hint)
        assertEquals(AgentPreferences.ID_BUILT_IN, resolved.id)
    }

    @Test
    fun a_stale_default_agent_id_does_not_run_a_legacy_agent() = signedIn {
        val s = store()
        seedLegacyAgent(s)
        // A saved default pointing at the legacy record, e.g. chosen before the
        // user ever signed in.
        s.fileOf("fluence_prefs")["agent_default_id"] = legacyAgentId

        assertEquals(
            "a stale default must fall back to the built-in, not run the legacy prompt",
            AgentPreferences.ID_BUILT_IN,
            AgentPreferences.getDefaultAgentId(s.context()),
        )
        assertFalse(
            "and it must not be settable as a default",
            run {
                AgentPreferences.setDefaultAgentId(s.context(), legacyAgentId)
                AgentPreferences.getDefaultAgentId(s.context()) == legacyAgentId
            },
        )
    }

    @Test
    fun a_stale_app_override_does_not_apply_a_legacy_style() = signedIn {
        val s = store()
        seedLegacyStyle(s)
        // An override recorded while signed out.
        AiCleanupPreferences.setOverride(s.context(), "com.example.app", legacyStyleId)

        assertFalse(AiCleanupPreferences.isKnownStyle(s.context(), legacyStyleId))
        assertNull(
            "a legacy style must never be applied to an app",
            AiCleanupPreferences.styleForPackage(s.context(), "com.example.app"),
        )
    }

    // ------------------------------------------------------------------
    // Account-owned records are untouched by this change
    // ------------------------------------------------------------------

    @Test
    fun account_owned_records_are_available_and_unlabelled() = signedIn {
        val s = store()
        val created = AgentPreferences.saveCustomAgent(s.context(), "Mine", "My prompt")!!

        val listed = AgentPreferences.loadCustomAgents(s.context()).first { it.id == created.id }
        assertTrue("an account-owned agent is fully available", listed.available)
        assertNull("and carries no unavailability reason", listed.unavailableReason)
        assertTrue(AgentPreferences.isKnownAgent(s.context(), created.id))

        val style = AiCleanupPreferences.saveCustomStyle(s.context(), "MineStyle", "My style prompt")!!
        val listedStyle = AiCleanupPreferences.loadCustomStyles(s.context()).first { it.id == style.id }
        assertTrue(listedStyle.available)
        assertNull(listedStyle.unavailableReason)
        assertTrue(AiCleanupPreferences.isKnownStyle(s.context(), style.id))
    }

    @Test
    fun no_synthetic_built_in_entry_is_added_to_the_list() = signedIn {
        val s = store()
        seedLegacyAgent(s)
        val ids = AgentPreferences.loadCustomAgents(s.context()).map { it.id }
        assertFalse(
            "the built-in agent must not be injected into the custom list",
            ids.contains(AgentPreferences.ID_BUILT_IN),
        )
    }

    // ------------------------------------------------------------------
    // Precedence, tombstones, and signed-out behaviour
    // ------------------------------------------------------------------

    @Test
    fun an_account_record_wins_over_a_shadowed_legacy_record() = signedIn {
        val s = store()
        seedLegacyAgent(s)
        // Same business key, now owned: the account copy must be what is shown.
        AccountScope.saveAgents(
            s.context(), hash,
            listOf(
                AccountScope.AccountAgent(
                    id = legacyAgentId, name = "Owned Agent", hint = "Owned prompt",
                    syncId = "sync-a", updatedAt = 10L, deviceId = "device-a",
                    deletedAt = null, dirty = false,
                )
            ),
        )

        val listed = AgentPreferences.loadCustomAgents(s.context())
        assertEquals("exactly one row for the shared id", 1, listed.count { it.id == legacyAgentId })
        val row = listed.first { it.id == legacyAgentId }
        assertEquals("Owned Agent", row.name)
        assertTrue("the owned copy is runnable", row.available)
    }

    @Test
    fun a_deleted_owned_agent_is_neither_listed_nor_runnable() = signedIn {
        val s = store()
        AccountScope.saveAgents(
            s.context(), hash,
            listOf(
                AccountScope.AccountAgent(
                    id = legacyAgentId, name = "Gone", hint = "p",
                    syncId = "sync-a", updatedAt = 10L, deviceId = "device-a",
                    deletedAt = 10L, dirty = false,
                )
            ),
        )

        assertTrue(
            "a tombstoned agent must not be listed",
            AgentPreferences.loadCustomAgents(s.context()).none { it.id == legacyAgentId },
        )
        assertFalse(AgentPreferences.isKnownAgent(s.context(), legacyAgentId))
    }

    @Test
    fun signed_out_legacy_records_are_available_and_unchanged() {
        // With no account there is no identity to be confused with, so legacy
        // records are DEVICE_LOCAL and fully usable offline. Withholding them
        // would brick the app for users who never sign in.
        val savedVerified = SyncAccounts.tokenVerified
        val savedAccount = SyncAccounts.cachedAccount
        SyncAccounts.clearAuthentication()
        SyncAccounts.cachedAccount = null
        try {
            val s = store()
            seedLegacyAgent(s)
            seedLegacyStyle(s)

            val agent = AgentPreferences.loadCustomAgents(s.context()).first { it.id == legacyAgentId }
            assertTrue(agent.available)
            assertNull(agent.unavailableReason)
            assertTrue(AgentPreferences.isKnownAgent(s.context(), legacyAgentId))

            val style = AiCleanupPreferences.loadCustomStyles(s.context()).first { it.id == legacyStyleId }
            assertTrue(style.available)
            assertTrue(AiCleanupPreferences.isKnownStyle(s.context(), legacyStyleId))
        } finally {
            SyncAccounts.clearAuthentication()
            if (savedVerified) SyncAccounts.publishAuthenticated(savedAccount) else {
                SyncAccounts.cachedAccount = savedAccount
            }
        }
    }
}