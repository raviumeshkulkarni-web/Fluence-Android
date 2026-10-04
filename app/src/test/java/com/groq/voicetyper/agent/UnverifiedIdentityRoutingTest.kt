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
 * The unverified-identity window both STAGE 6 reviewers blocked on.
 *
 * `SyncAccounts.tokenVerified` is in-memory only and false on every cold start
 * until a sync pass proves the identity against the access token — and forever
 * for a user who never enables sync. Local reads, writes and deletes must
 * therefore work on the best-known identity (verified email once proven,
 * persisted sign-in email before that), never collapsing "not yet verified"
 * into "signed out".
 *
 * Each test forces `tokenVerified == false` with a signed-in identity present
 * and asserts where the record landed. The failure these guard against is
 * silent divergence: a write that succeeds visibly but lands in the legacy
 * store, where no pass will ever upload it and STAGE 5 quarantines it.
 */
class UnverifiedIdentityRoutingTest {

    private val email = "test@example.com"
    private val hash = AccountHash.of(email)!!

    private inline fun <T> withUnverifiedIdentity(block: () -> T): T {
        val savedVerified = SyncAccounts.tokenVerified
        val savedAccount = SyncAccounts.cachedAccount
        SyncAccounts.clearAuthentication()
        SyncAccounts.cachedAccount = email
        assertFalse(
            "precondition: the identity must NOT be token-verified",
            SyncAccounts.tokenVerified,
        )
        try {
            return block()
        } finally {
            SyncAccounts.clearAuthentication()
            if (savedVerified) SyncAccounts.publishAuthenticated(savedAccount) else {
                SyncAccounts.cachedAccount = savedAccount
            }
        }
    }

    private fun legacyAgentJson(store: FakePrefsStore): String? =
        store.fileOf("fluence_prefs")["agent_custom_styles"] as? String

    private fun legacyStyleJson(store: FakePrefsStore): String? =
        store.fileOf("fluence_prefs")["ai_cleanup_custom_styles"] as? String

    // ------------------------------------------------------------------
    // Agents: writes land in the account file, never legacy
    // ------------------------------------------------------------------

    @Test
    fun an_agent_created_while_unverified_lands_in_the_account_file() = withUnverifiedIdentity {
        val store = FakePrefsStore()
        val ctx = store.context()

        val created = AgentPreferences.saveCustomAgent(ctx, "MyAgent", "Do things well")
        assertNotNull("the write must succeed", created)

        val inAccount = AccountScope.loadAgents(ctx, hash)
        assertEquals(1, inAccount.size)
        assertEquals("MyAgent", inAccount.single().name)
        assertNull(
            "nothing may be diverted to the never-synced legacy store",
            legacyAgentJson(store),
        )
    }

    @Test
    fun an_agent_created_while_unverified_is_immediately_runnable() = withUnverifiedIdentity {
        val store = FakePrefsStore()
        val ctx = store.context()

        val created = AgentPreferences.saveCustomAgent(ctx, "MyAgent", "Do things well")!!
        val visible = AgentPreferences.loadCustomAgents(ctx)
        assertEquals(listOf(created.id), visible.map { it.id })
        assertTrue(AgentPreferences.isKnownAgent(ctx, created.id))
        assertNotNull(AgentPreferences.resolveActiveAgent(ctx, created.id).hint)
    }

    @Test
    fun an_agent_deleted_while_unverified_leaves_a_tombstone() = withUnverifiedIdentity {
        val store = FakePrefsStore()
        val ctx = store.context()

        val created = AgentPreferences.saveCustomAgent(ctx, "MyAgent", "Do things well")!!
        AgentPreferences.deleteCustomAgent(ctx, created.id)

        // A row-removal here would let the next pass resurrect the agent from
        // another device. A missing tombstone would bring it straight back.
        val inAccount = AccountScope.loadAgents(ctx, hash)
        assertEquals(1, inAccount.size)
        assertNotNull("delete must write a tombstone, not remove the row", inAccount.single().deletedAt)
        assertNull(legacyAgentJson(store))
    }

    @Test
    fun read_write_and_delete_agree_while_unverified() = withUnverifiedIdentity {
        val store = FakePrefsStore()
        val ctx = store.context()

        val created = AgentPreferences.saveCustomAgent(ctx, "MyAgent", "Do things well")!!
        assertEquals(created.id, AgentPreferences.loadCustomAgents(ctx).single().id)
        AgentPreferences.deleteCustomAgent(ctx, created.id)
        // Tombstoned: still present for sync, no longer resolvable.
        assertNull(AgentPreferences.resolveActiveAgent(ctx, created.id).takeIf { !it.isBuiltIn })
    }

    // ------------------------------------------------------------------
    // Styles: the same contract (STAGE 6 B2)
    // ------------------------------------------------------------------

    @Test
    fun a_style_created_while_unverified_lands_in_the_account_file() = withUnverifiedIdentity {
        val store = FakePrefsStore()
        val ctx = store.context()

        val created = AiCleanupPreferences.saveCustomStyle(ctx, "MyStyle", "Rewrite briefly")
        assertNotNull("the write must succeed", created)

        val inAccount = AccountScope.loadStyles(ctx, hash)
        assertEquals(1, inAccount.size)
        assertEquals("MyStyle", inAccount.single().name)
        assertNull(
            "nothing may be diverted to the never-synced legacy store",
            legacyStyleJson(store),
        )
    }

    @Test
    fun a_style_created_while_unverified_is_immediately_usable() = withUnverifiedIdentity {
        val store = FakePrefsStore()
        val ctx = store.context()

        val created = AiCleanupPreferences.saveCustomStyle(ctx, "MyStyle", "Rewrite briefly")!!
        assertEquals(listOf(created.id), AiCleanupPreferences.loadCustomStyles(ctx).map { it.id })
        assertTrue(AiCleanupPreferences.isKnownStyle(ctx, created.id))
    }

    @Test
    fun a_style_deleted_while_unverified_leaves_a_tombstone() = withUnverifiedIdentity {
        val store = FakePrefsStore()
        val ctx = store.context()

        val created = AiCleanupPreferences.saveCustomStyle(ctx, "MyStyle", "Rewrite briefly")!!
        AiCleanupPreferences.deleteCustomStyle(ctx, created.id)

        val inAccount = AccountScope.loadStyles(ctx, hash)
        assertEquals(1, inAccount.size)
        assertNotNull("delete must write a tombstone, not remove the row", inAccount.single().deletedAt)
        assertNull(legacyStyleJson(store))
    }

    @Test
    fun a_legacy_style_is_not_runnable_while_signed_in_unverified() = withUnverifiedIdentity {
        // The pre-existing legacy store is unknown provenance. A signed-in
        // identity — even not yet re-verified this process — must not run it.
        val store = FakePrefsStore()
        val ctx = store.context()
        store.fileOf("fluence_prefs")["ai_cleanup_custom_styles"] =
            """[{"id":"custom:legacy1","name":"Legacy","hint":"Old prompt"}]"""

        assertTrue(
            "legacy record must be withheld while signed in",
            AiCleanupPreferences.loadCustomStyles(ctx).none { it.id == "custom:legacy1" },
        )
        assertFalse(AiCleanupPreferences.isKnownStyle(ctx, "custom:legacy1"))
    }

    // ------------------------------------------------------------------
    // Signed-out behaviour is unchanged
    // ------------------------------------------------------------------

    @Test
    fun signed_out_writes_still_use_the_legacy_store() {
        val savedVerified = SyncAccounts.tokenVerified
        val savedAccount = SyncAccounts.cachedAccount
        SyncAccounts.clearAuthentication()
        SyncAccounts.cachedAccount = null
        try {
            val store = FakePrefsStore()
            val ctx = store.context()

            val created = AgentPreferences.saveCustomAgent(ctx, "OfflineAgent", "Works offline")!!
            assertEquals(listOf(created.id), AgentPreferences.loadCustomAgents(ctx).map { it.id })
            assertNotNull(
                "with no sign-in at all, legacy is the only store and must be used",
                legacyAgentJson(store),
            )
        } finally {
            SyncAccounts.clearAuthentication()
            if (savedVerified) SyncAccounts.publishAuthenticated(savedAccount) else {
                SyncAccounts.cachedAccount = savedAccount
            }
        }
    }
}
