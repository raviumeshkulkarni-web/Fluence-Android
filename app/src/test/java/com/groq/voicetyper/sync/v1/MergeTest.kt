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
