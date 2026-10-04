package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 STAGE 3 — structural remote account partitioning.
 *
 * These tests pin the remote *path contract*. They are deliberately pure: no
 * network, no context, no account state. The path is a pure function of the
 * domain and the account hash, so the whole cross-platform isolation story can
 * be verified without a device.
 *
 * The identical literals appear in the Windows suite
 * `src-tauri/src/sync/account_partition.rs`. Any divergence splits every
 * account partition on every device, so it must fail a test, not surface at
 * runtime.
 */
class AccountPartitionTest {

    private val alice = "alice@example.com"
    private val bob = "bob@example.com"

    /**
     * The STAGE 1 cross-platform fixture, independently computed and asserted
     * identically in `windows-main/src-tauri/src/sync/metadata.rs`. This is the
     * vector the hardcoded path expectations below are built on, so the
     * cross-platform path contract is anchored to an already-verified hash
     * rather than to a value recomputed by the same code under test.
     */
    private val canonicalEmail = "test@example.com"
    private val canonicalHash =
        "973dfe463ec85785f5f95af5ba3906eedb2d931c24e69824a89ea65dba4e813b"
    private val canonicalSegment =
        "9d7f994b0d8cadd9d97315553dbb81c64fb0d9d53c702976aafd6a65069c12e2"

    // Two distinct accounts, derived rather than hardcoded: these tests are
    // about distinctness, and the derivation is already fixture-locked above.
    private val aliceHash = AccountHash.of(alice)!!
    private val bobHash = AccountHash.of(bob)!!

    // ------------------------------------------------------------------
    // Canonical cross-platform path fixtures
    // ------------------------------------------------------------------

    @Test
    fun agents_live_in_the_account_partition() {
        assertEquals(
            "fluence/v1/acct-$canonicalSegment/agents.json",
            AccountPartition.relativePath(DomainFile.AGENTS, canonicalHash),
        )
    }

    @Test
    fun styles_live_in_the_account_partition() {
        assertEquals(
            "fluence/v1/acct-$canonicalSegment/styles.json",
            AccountPartition.relativePath(DomainFile.STYLES, canonicalHash),
        )
    }

    @Test
    fun the_path_segment_is_a_separately_derived_value_not_the_account_hash() {
        // The folder segment must NOT be the account hash. The account hash
        // appears in `syncAccount` columns, `SyncMetadata` and local account
        // filenames, so reusing it here would let one observed value link a
        // Drive folder to an exported database or a file listing.
        val segment = AccountPartition.pathSegment(canonicalHash)!!
        assertNotEquals(
            "the Drive path segment must not reuse the account hash",
            canonicalHash,
            segment,
        )
        assertEquals(64, segment.length)
        assertTrue(segment.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun the_path_segment_is_derived_from_the_tagged_input() {
        // Documented derivation: SHA-256("fluence/acct-path/v1\0" + accountHash)
        assertEquals(
            canonicalSegment,
            AccountPartition.pathSegment(canonicalHash),
        )
        assertEquals("acct-$canonicalSegment", AccountPartition.folderName(canonicalHash))
    }

    @Test
    fun two_accounts_derive_two_unrelated_path_segments() {
        assertNotEquals(
            AccountPartition.pathSegment(aliceHash),
            AccountPartition.pathSegment(bobHash),
        )
    }

    @Test
    fun a_malformed_account_hash_yields_no_path_segment() {
        listOf(null, "", "garbage", canonicalHash.take(16), canonicalHash.uppercase())
            .forEach { assertNull(it.toString(), AccountPartition.pathSegment(it)) }
    }

    @Test
    fun agents_and_styles_share_one_partition_folder_per_account() {
        assertEquals(
            AccountPartition.relativePath(DomainFile.AGENTS, canonicalHash)!!.substringBeforeLast('/'),
            AccountPartition.relativePath(DomainFile.STYLES, canonicalHash)!!.substringBeforeLast('/'),
        )
    }

    // ------------------------------------------------------------------
    // Existing domains are NOT moved
    // ------------------------------------------------------------------

    @Test
    fun the_four_existing_domains_keep_their_flat_paths() {
        assertEquals(
            "fluence/v1/dictionary.json",
            AccountPartition.relativePath(DomainFile.DICTIONARY, aliceHash),
        )
        assertEquals(
            "fluence/v1/snippets.json",
            AccountPartition.relativePath(DomainFile.SNIPPETS, aliceHash),
        )
        assertEquals(
            "fluence/v1/stats.json",
            AccountPartition.relativePath(DomainFile.STATS, aliceHash),
        )
        assertEquals(
            "fluence/v1/settings.json",
            AccountPartition.relativePath(DomainFile.SETTINGS, aliceHash),
        )
    }

    @Test
    fun flat_domains_are_not_partitioned() {
        listOf(
            DomainFile.DICTIONARY, DomainFile.SNIPPETS,
            DomainFile.STATS, DomainFile.SETTINGS,
        ).forEach { assertFalse(it.name, AccountPartition.isPartitioned(it)) }
    }

    @Test
    fun flat_domain_paths_are_identical_for_every_account() {
        // Settings are device-global (D1b); dictionary/snippets/stats are shared
        // by partition. Changing account must not rename any of them.
        val a = AccountPartition.relativePath(DomainFile.DICTIONARY, aliceHash)
        val b = AccountPartition.relativePath(DomainFile.DICTIONARY, bobHash)
        assertEquals(a, b)
    }

    @Test
    fun no_existing_domain_file_was_moved() {
        // The frozen four must still be directly inside `v1`, never under a
        // partition folder, or every existing install would lose its data.
        listOf(
            DomainFile.DICTIONARY, DomainFile.SNIPPETS,
            DomainFile.STATS, DomainFile.SETTINGS,
        ).forEach {
            assertEquals(2, AccountPartition.relativePath(it, aliceHash)!!.count { c -> c == '/' })
        }
    }

    // ------------------------------------------------------------------
    // Cross-account isolation
    // ------------------------------------------------------------------

    @Test
    fun different_accounts_produce_different_partitions() {
        assertNotEquals(
            AccountPartition.relativePath(DomainFile.AGENTS, aliceHash),
            AccountPartition.relativePath(DomainFile.AGENTS, bobHash),
        )
    }

    @Test
    fun a_cannot_name_bs_partition() {
        // This is the whole point of partitioning: A has no way to construct B's
        // path, so "A adopts B's records" stops being a policy decision.
        val aPaths = listOf(DomainFile.AGENTS, DomainFile.STYLES).map {
            AccountPartition.relativePath(it, aliceHash)
        }
        val bPaths = listOf(DomainFile.AGENTS, DomainFile.STYLES).map {
            AccountPartition.relativePath(it, bobHash)
        }
        aPaths.forEach { p -> bPaths.forEach { q -> assertNotEquals(p, q) } }
    }

    @Test
    fun a_single_account_never_splits_its_own_agents_across_partitions() {
        // Repeated resolution must be stable, or a device would read a
        // different partition each pass and see partial data.
        val first = AccountPartition.relativePath(DomainFile.AGENTS, aliceHash)
        repeat(5) {
            assertEquals(first, AccountPartition.relativePath(DomainFile.AGENTS, aliceHash))
        }
    }

    // ------------------------------------------------------------------
    // Fail-closed on a malformed / missing hash
    // ------------------------------------------------------------------

    @Test
    fun an_unusable_hash_yields_no_path_for_partitioned_domains() {
        val bad = listOf(
            null, "", "   ",
            aliceHash.dropLast(1),                 // 63 chars
            aliceHash + "0",                       // 65 chars
            aliceHash.take(16),                    // the legacy 16-hex form
            aliceHash.uppercase(),                 // uppercase is not canonical
            "not-a-hash",
            "g" + aliceHash.drop(1),               // out-of-range hex char
        )
        bad.forEach { h ->
            assertNull(h.toString(), AccountPartition.folderName(h))
            assertNull(h.toString(), AccountPartition.relativePath(DomainFile.AGENTS, h))
            assertNull(h.toString(), AccountPartition.relativePath(DomainFile.STYLES, h))
            assertNull(h.toString(), AccountPartition.partitionFor(h))
        }
    }

    @Test
    fun a_malformed_hash_does_not_disable_the_flat_domains() {
        // dictionary/settings must remain syncable even when identity is odd;
        // they carry no account-scoped Agents/Styles payload.
        assertEquals(
            "fluence/v1/dictionary.json",
            AccountPartition.relativePath(DomainFile.DICTIONARY, "garbage"),
        )
    }

    @Test
    fun the_legacy_sixteen_hex_hash_is_rejected() {
        // Guards a specific regression: an early build used a 16-char hash.
        // Accepting it would silently address a different partition than the
        // one the account actually uses.
        assertFalse(AccountScope.validAccountHash(aliceHash.take(16)))
        assertNull(AccountPartition.folderName(aliceHash.take(16)))
    }

    // ------------------------------------------------------------------
    // Path traversal containment
    // ------------------------------------------------------------------

    @Test
    fun a_hash_cannot_escape_the_app_data_folder() {
        // A validated hash is 64 chars of [0-9a-f], so it cannot contain a
        // separator or a dot. This pins that property as a containment
        // guarantee rather than trusting the validator by inspection.
        val traversal = listOf(
            "../../etc/passwd",
            "..\\..\\windows\\system32",
            "acct-../../v1/dictionary.json",
            "./$aliceHash",
            "$aliceHash/agents.json",
            "$aliceHash\\styles.json",
            "....//....//x",
        )
        traversal.forEach { t ->
            val folder = AccountPartition.folderName(t)
            assertNull("must not produce a folder: $t", folder)
            assertNull("must not produce a path: $t", AccountPartition.relativePath(DomainFile.AGENTS, t))
        }
    }

    @Test
    fun every_generated_path_stays_inside_the_v1_root() {
        listOf(aliceHash, bobHash).forEach { h ->
            listOf(DomainFile.AGENTS, DomainFile.STYLES).forEach { d ->
                val p = AccountPartition.relativePath(d, h)!!
                assertTrue(p, p.startsWith("fluence/v1/"))
                assertFalse(p, p.contains(".."))
                assertFalse(p, p.contains("\\"))
            }
        }
    }

    @Test
    fun the_partition_segment_is_exactly_the_prefix_plus_a_64_hex_hash() {
        val folder = AccountPartition.folderName(aliceHash)!!
        val hash = folder.removePrefix(AccountPartition.FOLDER_PREFIX)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ------------------------------------------------------------------
    // Old-client compatibility
    // ------------------------------------------------------------------

    @Test
    fun an_old_client_querying_v1_by_exact_name_cannot_match_a_partition() {
        // An old client lists `fluence/v1` for a file whose name is exactly
        // "agents.json". The new location is a FOLDER named "acct-<hash>", so
        // the name never matches and the old client sees nothing new.
        val partition = AccountPartition.relativePath(DomainFile.AGENTS, aliceHash)!!
        val oldClientQuery = "fluence/v1/agents.json"
        assertNotEquals(oldClientQuery, partition)
        // The partition is one level deeper, so it is unreachable by a flat lookup.
        assertTrue(partition.count { it == '/' } > oldClientQuery.count { it == '/' })
        // And the leaf name still exists, but only inside the subfolder.
        assertTrue(partition.endsWith("/agents.json"))
    }

    @Test
    fun the_four_frozen_files_stay_reachable_by_an_old_client() {
        listOf(
            DomainFile.DICTIONARY, DomainFile.SNIPPETS,
            DomainFile.STATS, DomainFile.SETTINGS,
        ).forEach {
            val p = AccountPartition.relativePath(it, aliceHash)!!
            assertEquals("$it must remain directly inside v1", 2, p.count { c -> c == '/' })
        }
    }

    // ------------------------------------------------------------------
    // Hash derivation is shared with the partition path
    // ------------------------------------------------------------------

    @Test
    fun the_partition_uses_the_shared_hash_derivation() {
        // The account hash fed to the partition is the same shared derivation
        // both platforms use, and the path is built from its tagged segment.
        assertEquals(canonicalHash, AccountHash.of(canonicalEmail))
        assertEquals(
            "fluence/v1/acct-${AccountPartition.pathSegment(AccountHash.of(canonicalEmail))}/agents.json",
            AccountPartition.relativePath(DomainFile.AGENTS, AccountHash.of(canonicalEmail)),
        )
    }
}
