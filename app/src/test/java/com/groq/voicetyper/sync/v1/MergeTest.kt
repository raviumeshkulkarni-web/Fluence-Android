package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frozen v1.2 merge law: winner = max(updatedAt, deviceId) — pure LWW.
 * Tombstones are ordinary records: a newer delete beats an older live record,
 * and a newer re-creation beats an older tombstone. Older remote state can
 * never resurrect over a newer deletion. These tests mirror the Windows Rust
 * suite (sync::merge::tests) so both platforms assert identical laws.
 */
class MergeTest {

    private fun dict(
        syncId: String,
        spoken: String,
        updatedAt: Long,
        deletedAt: Long? = null,
        deviceId: String = "device-a",
        corrected: String = "fix"
    ) = DictionaryRecord(
        syncId = syncId,
        businessKey = DictionaryRecord.businessKeyOf(spoken),
        spoken = spoken,
        corrected = corrected,
        isEnabled = true,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        deviceId = deviceId
    )

    private fun snippet(
        syncId: String,
        trigger: String,
        updatedAt: Long,
        deletedAt: Long? = null,
        deviceId: String = "device-a",
        expansion: String = "expand"
    ) = SnippetRecord(
        syncId = syncId,
        businessKey = SnippetRecord.businessKeyOf(trigger),
        trigger = trigger,
        expansion = expansion,
        isEnabled = true,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        deviceId = deviceId
    )

    // ------------------------------------------------------------------
    // Cross-key syncId dedup (audit finding H3 / Phase D) - mirrors the
    // Windows Rust suite (sync::merge::tests). A rename moves a record's
    // business key while keeping its syncId; both platforms must collapse
    // the pre-rename and post-rename winners to one record.
    // ------------------------------------------------------------------

    @Test
    fun rename_same_syncId_different_snippet_keys_collapses_to_newest() {
        val old = snippet("s-rename-1", "my email", updatedAt = 100L)
        val new = snippet("s-rename-1", "my primary email", updatedAt = 200L)
        val merged = Merge.mergeSnippets(listOf(new), listOf(old))
        assertEquals("same syncId under two keys must collapse to one record", 1, merged.size)
        assertEquals("my primary email", merged[0].trigger)
    }

    // ------------------------------------------------------------------
    // H3 rename (delete-old + create-new) - mirrors Windows merge.rs. A
    // rename retires the old row (tombstone under the OLD key when pushed)
    // and creates a new row with a FRESH syncId under the NEW key. Both
    // platforms must agree on every scenario below, in both merge orders.
    // ------------------------------------------------------------------

    @Test
    fun rename_tombstone_plus_new_converges_both_orders() {
        val oldLive = dict("11111111-1111-4111-8111-111111111111", "hello", updatedAt = 100L, deviceId = "b")
        val oldDead = dict("11111111-1111-4111-8111-111111111111", "hello", updatedAt = 200L, deletedAt = 200L, deviceId = "a")
        val newLive = dict("22222222-2222-4222-8222-222222222222", "hallo", updatedAt = 200L, deviceId = "a")
        listOf(
            Pair(listOf(oldDead, newLive), listOf(oldLive)),
            Pair(listOf(oldLive), listOf(oldDead, newLive))
        ).forEach { (local, remote) ->
            val merged = Merge.mergeDictionaries(local, remote)
            assertEquals("tombstone(old) + live(new), nothing else", 2, merged.size)
            val old = merged.single { it.businessKey == "hello" }
            assertNotNull("the old entry stays dead", old.deletedAt)
            val new = merged.single { it.businessKey == "hallo" }
            assertNull(new.deletedAt)
            assertEquals("fix", new.corrected)
        }
    }

    @Test
    fun rename_repeated_merge_is_stable_and_stale_copies_do_not_resurrect() {
        val oldDead = dict("11111111-1111-4111-8111-111111111111", "hello", updatedAt = 200L, deletedAt = 200L, deviceId = "a")
        val newLive = dict("22222222-2222-4222-8222-222222222222", "hallo", updatedAt = 200L, deviceId = "a")
        val converged = Merge.mergeDictionaries(listOf(oldDead, newLive), emptyList())
        val stale = dict("11111111-1111-4111-8111-111111111111", "hello", updatedAt = 50L, deviceId = "c")
        val second = Merge.mergeDictionaries(converged, listOf(stale))
        assertEquals(2, second.size)
        assertNotNull(second.single { it.businessKey == "hello" }.deletedAt)
        assertNull(second.single { it.businessKey == "hallo" }.deletedAt)
    }

    @Test
    fun rename_concurrent_same_target_is_deterministic() {
        val aNew = dict("11111111-1111-4111-8111-111111111111", "hallo", updatedAt = 200L, deviceId = "a", corrected = "A")
        val bNew = dict("22222222-2222-4222-8222-222222222222", "hallo", updatedAt = 200L, deviceId = "a", corrected = "B")
        val ab = Merge.mergeDictionaries(listOf(aNew), listOf(bNew))
        val ba = Merge.mergeDictionaries(listOf(bNew), listOf(aNew))
        assertEquals(1, ab.size)
        assertEquals(1, ba.size)
        assertEquals("both orders agree", ab[0].corrected, ba[0].corrected)
        assertEquals("larger syncId wins the full tie", "B", ab[0].corrected)
    }

    @Test
    fun rename_delete_vs_rename_is_deterministic() {
        val oldLive = dict("11111111-1111-4111-8111-111111111111", "hello", updatedAt = 100L, deviceId = "b")
        val renameDead = dict("11111111-1111-4111-8111-111111111111", "hello", updatedAt = 200L, deletedAt = 200L, deviceId = "a")
        val renameNew = dict("22222222-2222-4222-8222-222222222222", "hallo", updatedAt = 200L, deviceId = "a")
        val deleteDead = dict("11111111-1111-4111-8111-111111111111", "hello", updatedAt = 300L, deletedAt = 300L, deviceId = "c")
        listOf(
            Pair(listOf(renameDead, renameNew), listOf(oldLive, deleteDead)),
            Pair(listOf(oldLive, deleteDead), listOf(renameDead, renameNew))
        ).forEach { (local, remote) ->
            val merged = Merge.mergeDictionaries(local, remote)
            val old = merged.single { it.businessKey == "hello" }
            assertNotNull(old.deletedAt)
            assertEquals("the newest tombstone wins the old key", 300L, old.updatedAt)
            val new = merged.single { it.businessKey == "hallo" }
            assertNull("the renamed entry survives the delete", new.deletedAt)
        }
    }

    @Test
    fun rename_same_syncId_different_dictionary_keys_collapses_to_newest() {
        val old = dict("d-rename-1", "oldword", updatedAt = 100L)
        val new = dict("d-rename-1", "newword", updatedAt = 200L)
        val merged = Merge.mergeDictionaries(listOf(new), listOf(old))
        assertEquals("same syncId under two keys must collapse to one record", 1, merged.size)
        assertEquals("newword", merged[0].spoken)
    }

    @Test
    fun rename_collapse_is_independent_of_merge_side() {
        val old = dict("d-rename-2", "oldword", updatedAt = 100L)
        val new = dict("d-rename-2", "newword", updatedAt = 200L)
        val ab = Merge.mergeDictionaries(listOf(new), listOf(old))
        val ba = Merge.mergeDictionaries(listOf(old), listOf(new))
        assertEquals(1, ab.size)
        assertEquals(1, ba.size)
        assertEquals(ab[0], ba[0])
    }

    @Test
    fun rename_collapse_keeps_newer_tombstone_over_older_live() {
        val live = dict("d-rename-3", "newword", updatedAt = 100L, deviceId = "device-a")
        val tomb = dict("d-rename-3", "oldword", updatedAt = 200L, deletedAt = 200L, deviceId = "device-b")
        val merged = Merge.mergeDictionaries(listOf(live), listOf(tomb))
        assertEquals(1, merged.size)
        assertNotNull("the newer tombstone must win even across keys", merged[0].deletedAt)
    }

    @Test
    fun rename_collapse_keeps_newer_live_over_older_tombstone() {
        val tomb = dict("d-rename-4", "oldword", updatedAt = 100L, deletedAt = 100L, deviceId = "device-a")
        val live = dict("d-rename-4", "newword", updatedAt = 200L, deviceId = "device-b")
        val merged = Merge.mergeDictionaries(listOf(tomb), listOf(live))
        assertEquals(1, merged.size)
        assertNull("the newer live record must win even across keys", merged[0].deletedAt)
        assertEquals("newword", merged[0].spoken)
    }

    @Test
    fun distinct_syncIds_still_yield_distinct_records() {
        val a = dict("d-distinct-1", "alpha", updatedAt = 100L)
        val b = dict("d-distinct-2", "beta", updatedAt = 200L)
        val merged = Merge.mergeDictionaries(listOf(a, b), emptyList())
        assertEquals(2, merged.size)
    }

    @Test
    fun rename_collapse_is_stable_across_repeated_passes() {
        // First pass collapses the rename; later passes — including one
        // against a stale pre-rename copy from a device that never saw the
        // rename — must stay collapsed and converge, never flap back to two.
        val new = dict("d-repeat-1", "newword", updatedAt = 200L)
        val old = dict("d-repeat-1", "oldword", updatedAt = 100L)
        val first = Merge.mergeDictionaries(listOf(new), listOf(old))
        assertEquals(1, first.size)

        val stale = dict("d-repeat-1", "oldword", updatedAt = 50L, deviceId = "device-c")
        val second = Merge.mergeDictionaries(first, listOf(stale))
        assertEquals("a stale pre-rename copy must not resurrect the duplicate", 1, second.size)
        assertEquals("newword", second[0].spoken)

        val third = Merge.mergeDictionaries(second, emptyList())
        assertEquals(1, third.size)
    }

    @Test
    fun rename_full_tie_keeps_smallest_businessKey() {
        // Same syncId, same updatedAt, same deviceId under two keys: the
        // syncId tiebreak is inert inside the group, so determinism comes
        // from the pre-collapse key sort — smallest business key wins,
        // matching Windows' BTreeMap order. Practically unreachable (a
        // rename restamps updatedAt), pinned so the order is total.
        val old = dict("d-tie-1", "oldword", updatedAt = 100L)
        val new = dict("d-tie-1", "newword", updatedAt = 100L)
        val ab = Merge.mergeDictionaries(listOf(new), listOf(old))
        val ba = Merge.mergeDictionaries(listOf(old), listOf(new))
        assertEquals(1, ab.size)
        assertEquals(1, ba.size)
        assertEquals("newword", ab[0].spoken)
        assertEquals("newword", ba[0].spoken)
    }

    // ------------------------------------------------------------------
    // Total ordering: a full tie must resolve identically on every device
    // ------------------------------------------------------------------

    @Test
    fun tie_on_timestamp_and_device_resolves_by_sync_id_not_by_side() {
        // Mirrors the Windows test of the same name. Two records tie on both
        // updatedAt and deviceId, so the winner must be decided by a stable
        // content-independent discriminator (syncId) rather than by whether the
        // record happened to arrive as the local or the remote copy. Otherwise
        // two devices holding different payloads for this key each keep their
        // own copy and re-push to each other forever.
        val local = dict(
            syncId = "ffffffff-ffff-4fff-8fff-ffffffffffff",
            spoken = "hello",
            updatedAt = 100L,
            corrected = "LOCAL"
        )
        val remote = dict(
            syncId = "00000000-0000-4000-8000-000000000001",
            spoken = "hello",
            updatedAt = 100L,
            corrected = "REMOTE"
        )
        val merged = Merge.mergeDictionaries(listOf(local), listOf(remote))
        assertEquals(1, merged.size)
        assertEquals(
            "a full (updatedAt, deviceId) tie must break deterministically by " +
                "syncId, not by whether the record happened to be local or remote",
            "LOCAL",
            merged[0].corrected
        )
    }

    // ------------------------------------------------------------------
    // Pure-LWW delete / re-creation semantics
    // ------------------------------------------------------------------

    @Test
    fun newer_delete_beats_older_live() {
        val live = dict("s1", "gonna", updatedAt = 1000L)
        val dead = dict("s2", "gonna", updatedAt = 2000L, deletedAt = 2000L)
        assertEquals(dead, Merge.pickDictionaryWinner(live, dead))
        assertEquals(dead, Merge.pickDictionaryWinner(dead, live))
    }

    @Test
    fun newer_recreation_beats_older_tombstone() {
        // User deleted at t=1000, re-added at t=2000 → live again.
        val tomb = dict("s1", "gonna", updatedAt = 1000L, deletedAt = 1000L)
        val recreated = dict("s2", "gonna", updatedAt = 2000L)
        assertEquals(recreated, Merge.pickDictionaryWinner(tomb, recreated))
        assertEquals(recreated, Merge.pickDictionaryWinner(recreated, tomb))
        assertNull(Merge.pickDictionaryWinner(tomb, recreated).deletedAt)
    }

    @Test
    fun older_live_never_resurrects_over_newer_tombstone() {
        // An offline device's stale edit must not undo a newer deletion.
        val staleEdit = dict("s1", "gonna", updatedAt = 500L)
        val tomb = dict("s2", "gonna", updatedAt = 2000L, deletedAt = 2000L)
        assertNotNull(Merge.pickDictionaryWinner(staleEdit, tomb).deletedAt)
    }

    @Test
    fun delete_vs_concurrent_edit_is_deterministic_regardless_of_order() {
        val edit = dict("a1", "teh", updatedAt = 150L, corrected = "edited")
        val tomb = dict("b1", "teh", updatedAt = 150L, deletedAt = 150L, deviceId = "device-z")
        val ab = Merge.pickDictionaryWinner(edit, tomb)
        val ba = Merge.pickDictionaryWinner(tomb, edit)
        assertEquals(ab.deletedAt != null, ba.deletedAt != null)
        assertEquals(ab.deviceId, ba.deviceId)
    }

    @Test
    fun newer_updatedAt_wins() {
        val older = dict("s1", "gonna", updatedAt = 1000L)
        val newer = dict("s2", "gonna", updatedAt = 2000L)
        assertEquals(newer, Merge.pickDictionaryWinner(older, newer))
        assertEquals(newer, Merge.pickDictionaryWinner(newer, older))
    }

    @Test
    fun deviceId_breaks_exact_ties_deterministically() {
        val a = dict("s1", "gonna", updatedAt = 1000L, deviceId = "device-b")
        val b = dict("s2", "gonna", updatedAt = 1000L, deviceId = "device-a")
        val winner = Merge.pickDictionaryWinner(a, b)
        assertEquals(winner.deviceId, maxOf(a.deviceId, b.deviceId))
        // Order independence
        assertEquals(winner, Merge.pickDictionaryWinner(b, a))
    }

    @Test
    fun clock_isWinner_is_pure_lww() {
        assertTrue(Clock.isWinner(10L, "a", 5L, "b"))
        assertFalse(Clock.isWinner(5L, "a", 10L, "b"))
        // Tie → lexicographic deviceId
        assertTrue(Clock.isWinner(5L, "b", 5L, "a"))
        assertFalse(Clock.isWinner(5L, "a", 5L, "b"))
        // A tombstone is just a record: newest wins either way.
        assertTrue(Clock.isWinner(20L, "a", 5L, "z"))
        assertFalse(Clock.isWinner(5L, "a", 20L, "z"))
    }

    @Test
    fun clock_nextUpdatedAt_is_monotonic_vs_maxSeen() {
        assertEquals(1500L, Clock.nextUpdatedAt(wallMs = 1500L, maxSeen = 1000L))
        assertEquals(1001L, Clock.nextUpdatedAt(wallMs = 500L, maxSeen = 1000L))
    }

    @Test
    fun clock_nextUpdatedAt_caps_sticky_future_maxSeen() {
        // A runaway wall clock (maxSeen far in the future vs the given wall)
        // must not inflate the stamp beyond wall + MAX_CLOCK_SKEW_MS.
        val wall = 1_000_000L
        val runaway = wall + 4 * Clock.MAX_CLOCK_SKEW_MS
        assertEquals(
            wall + Clock.MAX_CLOCK_SKEW_MS,
            Clock.nextUpdatedAt(wallMs = wall, maxSeen = runaway)
        )
        // But a small legitimate future maxSeen (accurate peer clock) is NOT
        // over-capped: it stays dominant so a genuine remote record wins LWW.
        val smallFuture = wall + 120_000L
        assertEquals(smallFuture + 1L, Clock.nextUpdatedAt(wallMs = wall, maxSeen = smallFuture))
    }

    @Test
    fun clock_stamps_stay_monotone_while_wall_lags_the_floor() {
        // Post-jump state: the floor sits far ahead of wall. Successive stamps
        // must not regress (cap binds both calls, wall advance carries them).
        val wall = 1_000_000L
        val seen = wall + 30 * 60_000L
        val s1 = Clock.nextUpdatedAt(wallMs = wall, maxSeen = seen)
        val s2 = Clock.nextUpdatedAt(wallMs = wall + 1L, maxSeen = seen)
        assertEquals(wall + Clock.MAX_CLOCK_SKEW_MS, s1)
        assertTrue("stamps never regress while wall lags the floor", s2 >= s1)
    }

    // ------------------------------------------------------------------
    // businessKey
    // ------------------------------------------------------------------

    @Test
    fun businessKey_is_lower_trimmed() {
        assertEquals("gonna", DictionaryRecord.businessKeyOf("  GONNA  "))
        assertEquals("addr", SnippetRecord.businessKeyOf("\tAddr\n"))
    }

    @Test
    fun merge_picks_one_winner_per_businessKey() {
        val local = listOf(dict("l1", "Gonna", updatedAt = 1000L, corrected = "local-fix"))
        val remote = listOf(
            dict("r1", "gonna", updatedAt = 2000L, corrected = "remote-fix"),
            dict("r2", "asap", updatedAt = 500L, corrected = "ASAP")
        )
        val merged = Merge.mergeDictionaries(local, remote)
        assertEquals(2, merged.size)
        val gonna = merged.first { it.businessKey == "gonna" }
        assertEquals("remote-fix", gonna.corrected)
        assertEquals(2000L, gonna.updatedAt)
    }

    @Test
    fun offline_edits_converge_regardless_of_merge_order() {
        val e1 = dict("d1", "w", updatedAt = 100L, deviceId = "dev-1", corrected = "one")
        val e2 = dict("d2", "w", updatedAt = 200L, deviceId = "dev-2", corrected = "two")
        val e3 = dict("d3", "w", updatedAt = 300L, deviceId = "dev-3", corrected = "three")
        val r1 = Merge.mergeDictionaries(Merge.mergeDictionaries(listOf(e1), listOf(e2)), listOf(e3))
        val r2 = Merge.mergeDictionaries(Merge.mergeDictionaries(listOf(e3), listOf(e1)), listOf(e2))
        val r3 = Merge.mergeDictionaries(Merge.mergeDictionaries(listOf(e2), listOf(e3)), listOf(e1))
        assertEquals("three", r1[0].corrected)
        assertEquals("three", r2[0].corrected)
        assertEquals("three", r3[0].corrected)
    }

    @Test
    fun duplicate_creation_on_two_devices_yields_one_winner() {
        val a = dict("11111111-1111-4111-8111-111111111111", "github", updatedAt = 100L, deviceId = "dev-a")
        val b = dict("22222222-2222-4222-8222-222222222222", "github", updatedAt = 200L, deviceId = "dev-b")
        val merged = Merge.mergeDictionaries(listOf(a), listOf(b))
        assertEquals(1, merged.size)
    }

    @Test
    fun expansion_and_corrected_are_byte_exact_through_merge() {
        val weird = "  keep   internal spaces\tand tabs "
        val local = listOf(dict("l1", "sig", updatedAt = 1000L, corrected = weird))
        val merged = Merge.mergeDictionaries(local, emptyList())
        assertEquals(weird, merged[0].corrected)
    }

    // ------------------------------------------------------------------
    // Settings — frozen five keys, per-key LWW
    // ------------------------------------------------------------------

    private fun setting(key: String, value: String, at: Long, dev: String = "d1") =
        SettingsRecord(key = key, value = value, updatedAt = at, deviceId = dev)

    @Test
    fun settings_merge_per_key_lww() {
        val local = listOf(
            setting("language", "en", 1000L),
            setting("snippets_enabled", "true", 1000L)
        )
        val remote = listOf(
            setting("language", "de", 2000L, dev = "d2"),
            setting("ai_polish_style", "formal", 500L)
        )
        val merged = Merge.mergeSettings(local, remote)
        assertEquals(3, merged.size)
        assertEquals("de", merged.first { it.key == "language" }.value)
        assertEquals("true", merged.first { it.key == "snippets_enabled" }.value)
        assertEquals("formal", merged.first { it.key == "ai_polish_style" }.value)
    }

    @Test
    fun settings_unknown_keys_never_enter_the_domain() {
        val merged = Merge.mergeSettings(
            emptyList(),
            listOf(setting("theme", "dark", 1L), setting("haptics_enabled", "true", 1L), setting("language", "en", 1L))
        )
        assertEquals(listOf("language"), merged.map { it.key })
    }

    @Test
    fun settings_full_tie_resolves_by_value_larger_wins() {
        // H2 (Windows parity, merge.rs): same key, same updatedAt, same
        // deviceId, different value — larger value wins on BOTH merge orders.
        // Side-dependent outcomes would re-push forever.
        val local = listOf(setting("language", "en", 100L, dev = "a"))
        val remote = listOf(setting("language", "fr", 100L, dev = "a"))
        assertEquals("fr", Merge.mergeSettings(local, remote).single().value)
        assertEquals("fr", Merge.mergeSettings(remote, local).single().value)
    }

    @Test
    fun settings_sentinel_full_tie_resolves_by_value() {
        // H2: a full tie inside the adoption-sentinel class also resolves by
        // value — and the winner is the ORIGINAL record, never a copy (the
        // stored sentinel differs from the effective time, so a copy-based
        // comparison could mistake the winner).
        val local = listOf(setting("language", "en", 1700000000000L, dev = "d"))
        val remote = listOf(setting("language", "fr", 1700000000000L, dev = "d"))
        val out = Merge.mergeSettings(local, remote).single()
        assertEquals("fr", out.value)
        assertEquals(1700000000000L, out.updatedAt)
        assertEquals("fr", Merge.mergeSettings(remote, local).single().value)
    }

    @Test
    fun stats_full_tie_resolves_by_payload_larger_wins() {
        // H2 (Windows parity, merge.rs): same eventId, same updatedAt, same
        // deviceId, different payload — larger wordCount wins on BOTH orders.
        val local = StatRecord("evt-1", "2026-08-20", 100, 84000, updatedAt = 1L, deviceId = "d1")
        val remote = local.copy(wordCount = 999)
        assertEquals(999, Merge.mergeStats(listOf(local), listOf(remote)).single().wordCount)
        assertEquals(999, Merge.mergeStats(listOf(remote), listOf(local)).single().wordCount)
    }

    @Test
    fun stats_full_tie_same_day_resolves_by_timestamp_then_words() {
        // H2: day ties fall through to timestampMs, then wordCount — larger
        // wins regardless of merge order.
        val base = StatRecord("evt-2", "2026-08-20", 100, 84000, timestampMs = 100L, updatedAt = 1L, deviceId = "d1")
        val laterTs = base.copy(timestampMs = 200L)
        assertEquals(200L, Merge.mergeStats(listOf(base), listOf(laterTs)).single().timestampMs)
        assertEquals(200L, Merge.mergeStats(listOf(laterTs), listOf(base)).single().timestampMs)
    }

    @Test
    fun settings_frozen_five_keys_only() {
        assertEquals(
            setOf("language", "dictionary_enabled", "snippets_enabled", "auto_learn_enabled", "ai_polish_style"),
            SettingsRecord.ALLOWED_KEYS
        )
    }

    @Test
    fun settings_adoption_loses_to_existing_remote() {
        val adoption = SettingsRecord("language", "en", updatedAt = 0L, deviceId = "d1")
        val remote = SettingsRecord("language", "de", updatedAt = 1000L, deviceId = "d2")
        val merged = Merge.mergeSettings(listOf(adoption), listOf(remote))
        assertEquals("de", merged.first { it.key == "language" }.value)
        assertEquals(1000L, merged.first { it.key == "language" }.updatedAt)
    }

    @Test
    fun settings_adoption_winner_gets_stamped_when_no_remote() {
        val adoption = SettingsRecord("language", "en", updatedAt = 0L, deviceId = "d1")
        val merged = Merge.mergeSettings(listOf(adoption), emptyList())
        assertEquals(1, merged.size)
        val winner = merged[0]
        assertEquals(1700000000000L, winner.updatedAt)
    }

    // ------------------------------------------------------------------
    // C1 differential — the adoption sentinel must be READ BACK as the
    // adoption class. merge_settings itself emits 1700000000000 on output
    // (see settings_adoption_winner_gets_stamped_when_no_remote), so that
    // value is a first-observation marker, not a real Nov-2023 timestamp.
    // Windows must produce identical winners for identical inputs.
    // ------------------------------------------------------------------

    @Test
    fun c1_sentinel_vs_real_timestamp_remote_wins() {
        val localStamp = SettingsRecord("language", "en", updatedAt = 1700000000000L, deviceId = "d1")
        val remote = SettingsRecord("language", "de", updatedAt = 100L, deviceId = "d2")
        val winner = Merge.mergeSettings(listOf(localStamp), listOf(remote))
            .first { it.key == "language" }
        assertEquals("a real timestamp must beat the adoption sentinel", "de", winner.value)
        assertEquals(100L, winner.updatedAt)
    }

    @Test
    fun c1_zero_vs_zero_adversarial_device_ids() {
        val localStamp = SettingsRecord("language", "en", updatedAt = 0L, deviceId = "zzz-local")
        val remoteStamp = SettingsRecord("language", "de", updatedAt = 0L, deviceId = "aaa-remote")
        val winner = Merge.mergeSettings(listOf(localStamp), listOf(remoteStamp))
            .first { it.key == "language" }
        assertEquals("greater deviceId breaks the adoption tie", "zzz-local", winner.deviceId)
        assertEquals("en", winner.value)
        assertEquals("adoption class re-stamps on output", 1700000000000L, winner.updatedAt)
    }

    @Test
    fun c1_sentinel_vs_sentinel_adversarial_device_ids() {
        val localStamp = SettingsRecord("language", "en", updatedAt = 1700000000000L, deviceId = "aaa-local")
        val remoteStamp = SettingsRecord("language", "de", updatedAt = 1700000000000L, deviceId = "zzz-remote")
        val winner = Merge.mergeSettings(listOf(localStamp), listOf(remoteStamp))
            .first { it.key == "language" }
        assertEquals("greater deviceId breaks the sentinel tie", "zzz-remote", winner.deviceId)
        assertEquals("de", winner.value)
        assertEquals("no re-stamp of an already-stamped value", 1700000000000L, winner.updatedAt)
    }

    // ------------------------------------------------------------------
    // Stats — union dedup eventId
    // ------------------------------------------------------------------

    @Test
    fun stats_union_dedups_by_eventId() {
        val shared = StatRecord("evt-1", "2026-08-20", 100, 84000, updatedAt = 1L, deviceId = "d1")
        val localOnly = StatRecord("evt-2", "2026-08-21", 50, 40000, updatedAt = 2L, deviceId = "d1")
        val remoteOnly = StatRecord("evt-3", "2026-08-19", 70, 70000, updatedAt = 3L, deviceId = "d2")
        val merged = Merge.mergeStats(listOf(shared, localOnly), listOf(shared.copy(updatedAt = 99L), remoteOnly))
        assertEquals(setOf("evt-1", "evt-2", "evt-3"), merged.map { it.eventId }.toSet())
        // Display-time summation over the merged set is how X+Y is produced.
        assertEquals(220, merged.sumOf { it.wordCount })
    }
}
