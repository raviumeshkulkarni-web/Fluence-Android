package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * F24 — `!!` on re-parsed bytes.
 *
 * `mergeValidDuplicatePayloads` consolidated same-name valid siblings by
 * RE-PARSING each payload and asserting the result with `!!`. That was safe only
 * because `getDomain` had pre-proved parseability ~130 lines earlier — an
 * invariant recorded in no comment and enforced nowhere. One drift between the
 * two parses and a merge path crashed the whole pass with a
 * `NullPointerException`.
 *
 * The contract now: an unparseable payload is SKIPPED (its siblings still merge
 * and the remote files are left intact), and a batch where NOTHING parses is
 * refused outright — never uploaded as an empty envelope, which would delete the
 * very rows consolidation exists to preserve.
 */
class DuplicateMergeReparseFixTest {

    private val past = 1_700_000_000_000L
    private val uuidA = "00000000-0000-4000-8000-000000000001"
    private val uuidB = "00000000-0000-4000-8000-000000000002"

    private fun dictionary(spoken: String, corrected: String, id: String) = DictionaryDomain(
        entries = listOf(
            DictionaryRecord(
                syncId = id,
                businessKey = DictionaryRecord.businessKeyOf(spoken),
                spoken = spoken,
                corrected = corrected,
                isEnabled = true,
                updatedAt = past,
                deletedAt = null,
                deviceId = "device-a",
            )
        )
    )

    private fun snippet(trigger: String, id: String) = SnippetDomain(
        entries = listOf(
            SnippetRecord(
                syncId = id,
                businessKey = SnippetRecord.businessKeyOf(trigger),
                trigger = trigger,
                expansion = "123 Main St",
                isEnabled = true,
                updatedAt = past,
                deletedAt = null,
                deviceId = "device-a",
            )
        )
    )

    private fun stats(day: String, id: String) = StatsDomain(
        entries = listOf(
            StatRecord(
                eventId = id,
                day = day,
                wordCount = 5,
                durationMs = 1_000L,
                updatedAt = past,
                deviceId = "device-a",
                timestampMs = past,
                chars = 25,
            )
        )
    )

    private fun settings(value: String) = SettingsDomain(
        entries = listOf(
            SettingsRecord(
                key = "language",
                value = value,
                updatedAt = past,
                deviceId = "device-a",
            )
        )
    )

    private fun agent(name: String, id: String) = AgentDomain(
        entries = listOf(
            AgentRecord(
                syncId = id,
                businessKey = AgentRecord.PREFIX + id,
                name = name,
                hint = "a hint",
                updatedAt = past,
                deletedAt = null,
                deviceId = "device-a",
            )
        )
    )

    private fun style(name: String, id: String) = StyleDomain(
        entries = listOf(
            StyleRecord(
                syncId = id,
                businessKey = StyleRecord.PREFIX + id,
                name = name,
                hint = "a hint",
                updatedAt = past,
                deletedAt = null,
                deviceId = "device-a",
            )
        )
    )

    private val store = AppDataDriveStore("test-token")

    @Test
    fun an_unparseable_sibling_is_skipped_and_the_rest_still_merge() {
        val good = DomainSerializer.serializeDictionary(dictionary("gonna", "going to", uuidA))
            .toByteArray()
        val merged = store.mergeValidDuplicatePayloads(
            DomainFile.DICTIONARY,
            listOf(good, "not json at all".toByteArray()),
        )
        val parsed = DomainSerializer.parseDictionary(merged)
        assertTrue("the surviving sibling must merge", parsed != null && parsed.entries.isNotEmpty())
        assertEquals("gonna", parsed!!.entries.first { it.spoken == "gonna" }.spoken)
    }

    @Test
    fun a_wrong_envelope_version_is_skipped_rather_than_crashing() {
        val good = DomainSerializer.serializeDictionary(dictionary("later", "later", uuidA))
            .toByteArray()
        val future = """{"v":2,"entries":[]}""".toByteArray()
        val merged = store.mergeValidDuplicatePayloads(DomainFile.DICTIONARY, listOf(good, future))
        assertEquals(1, DomainSerializer.parseDictionary(merged)!!.entries.size)
    }

    @Test
    fun a_batch_where_nothing_parses_is_refused_never_an_empty_upload() {
        try {
            store.mergeValidDuplicatePayloads(
                DomainFile.DICTIONARY,
                listOf("garbage".toByteArray(), "".toByteArray()),
            )
            fail("uploading an empty envelope would delete the rows consolidation preserves")
        } catch (e: SyncError.Retryable) {
            assertTrue(e.message!!.contains("intact"))
        }
    }

    @Test
    fun the_retryable_refusal_is_not_a_null_pointer_crash() {
        // The pre-fix code raised NPE here, which runPass mapped to RETRYABLE by
        // accident via its catch-all; the point is that the failure is now a
        // deliberate, classified SyncError and never a platform NPE.
        val thrown = runCatching {
            store.mergeValidDuplicatePayloads(DomainFile.DICTIONARY, listOf("nope".toByteArray()))
        }.exceptionOrNull()
        assertTrue(
            "expected a classified SyncError, was ${thrown?.javaClass?.name}",
            thrown is SyncError.Retryable,
        )
        assertTrue(
            "a NullPointerException here is exactly the regression",
            thrown !is NullPointerException,
        )
    }

    /** Every branch must tolerate one bad sibling, not just dictionary. */
    @Test
    fun every_domain_branch_tolerates_one_unparseable_sibling() {
        val cases = listOf(
            Triple(
                DomainFile.DICTIONARY,
                DomainSerializer.serializeDictionary(dictionary("gonna", "going to", uuidA)).toByteArray(),
                "00000000-0000-4000-8000-000000000001",
            ),
            Triple(
                DomainFile.SNIPPETS,
                DomainSerializer.serializeSnippets(snippet("addr", uuidA)).toByteArray(),
                "00000000-0000-4000-8000-000000000001",
            ),
            Triple(
                DomainFile.STATS,
                DomainSerializer.serializeStats(stats("2026-01-02", uuidA)).toByteArray(),
                "00000000-0000-4000-8000-000000000001",
            ),
            Triple(
                DomainFile.SETTINGS,
                // Settings carry no syncId; the key is the identity on the wire.
                DomainSerializer.serializeSettings(settings("en")).toByteArray(),
                """"key":"language"""",
            ),
            Triple(
                DomainFile.AGENTS,
                DomainSerializer.serializeAgents(agent("Writer", uuidA)).toByteArray(),
                "00000000-0000-4000-8000-000000000001",
            ),
            Triple(
                DomainFile.STYLES,
                DomainSerializer.serializeStyles(style("Technical", uuidA)).toByteArray(),
                "00000000-0000-4000-8000-000000000001",
            ),
        )
        for ((domain, good, marker) in cases) {
            val merged = store.mergeValidDuplicatePayloads(
                domain,
                listOf(good, "}{ not json".toByteArray()),
            )
            val text = String(merged, Charsets.UTF_8)
            assertTrue(
                "$domain must merge the surviving sibling, got '$text'",
                text.contains(marker),
            )
            assertTrue("$domain envelope must stay well formed", text.startsWith("""{"v":1,"entries":["""))
            assertTrue(
                "$domain must re-parse after consolidation",
                DomainSerializer.parseDictionary(merged) != null ||
                    DomainSerializer.parseSnippets(merged) != null ||
                    DomainSerializer.parseStats(merged) != null ||
                    DomainSerializer.parseSettings(merged) != null ||
                    DomainSerializer.parseAgents(merged) != null ||
                    DomainSerializer.parseStyles(merged) != null,
            )
        }
    }

    /** The healthy path is unchanged: two valid siblings still consolidate. */
    @Test
    fun two_valid_siblings_still_consolidate_through_lww() {
        val merged = store.mergeValidDuplicatePayloads(
            DomainFile.DICTIONARY,
            listOf(
                DomainSerializer.serializeDictionary(dictionary("gonna", "going to", uuidA)).toByteArray(),
                DomainSerializer.serializeDictionary(dictionary("wanna", "want to", uuidB)).toByteArray(),
            ),
        )
        assertEquals(2, DomainSerializer.parseDictionary(merged)!!.entries.size)
    }
}