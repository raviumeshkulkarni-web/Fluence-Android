package com.groq.voicetyper.sync.wire

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireRecordTest {

    private val fixture1: ByteArray = fixture("00000000-0000-4000-8000-000000000001")
    private val fixture2: ByteArray = fixture("00000000-0000-4000-8000-000000000002")
    private val fixture3: ByteArray = fixture("00000000-0000-4000-8000-000000000003")
    private val fixture4: ByteArray = fixture("00000000-0000-4000-8000-000000000004")
    private val fixture5: ByteArray = fixture("00000000-0000-4000-8000-000000000005")
    private val fixture6: ByteArray = fixture("00000000-0000-4000-8000-000000000006")
    private val fixture7: ByteArray = fixture("00000000-0000-4000-8000-000000000007")

    private fun fixture(name: String): ByteArray =
        checkNotNull(WireRecordTest::class.java.getResourceAsStream("/sync/$name.json")) {
            "missing fixture $name"
        }.readBytes()

    private fun parseOk(bytes: ByteArray, basename: String): WireRecord = when (val r = parse(bytes, basename)) {
        is ParseResult.Ok -> r.record
        is ParseResult.Err -> error("expected Ok, got ${r.reason}")
    }

    private fun parseErr(bytes: ByteArray, basename: String): InvalidReason = when (val r = parse(bytes, basename)) {
        is ParseResult.Ok -> error("expected Err, got $r")
        is ParseResult.Err -> r.reason
    }

    private fun roundtrip(bytes: ByteArray, basename: String): Pair<WireRecord, String> {
        val rec = parseOk(bytes, basename)
        val json = rec.toJson()
        val rec2 = parseOk(json.toByteArray(), basename)
        assertEquals("roundtrip must be lossless", rec, rec2)
        return rec2 to json
    }

    @Test
    fun fresh_android_record_roundtrip() {
        val (rec, json) = roundtrip(fixture1, "00000000-0000-4000-8000-000000000001")
        assertEquals(1, rec.v)
        assertEquals("00000000-0000-4000-8000-000000000001", rec.id)
        assertEquals(1713456000123L, rec.createdAt)
        assertNull(rec.deletedAt)
        assertEquals("Meeting notes: rename the module before the demo.", rec.text)
        assertEquals("transcription", rec.mode)
        assertEquals(8400L, rec.durationMs)
        assertEquals("groq", rec.provider)
        assertEquals("whisper-large-v3", rec.model)
        assertEquals("en", rec.language)
        assertTrue(json.contains("\"v\":1"))
        assertTrue(json.contains("\"model\":\"whisper-large-v3\""))
        assertTrue(json.contains("\"language\":\"en\""))
    }

    @Test
    fun minimal_windows_record_roundtrip() {
        val (rec, json) = roundtrip(fixture2, "00000000-0000-4000-8000-000000000002")
        assertEquals(1200L, rec.durationMs)
        assertEquals("openai", rec.provider)
        assertNull(rec.model)
        assertNull(rec.language)
        assertTrue(json.contains("\"model\":null"))
        assertTrue(json.contains("\"language\":null"))
    }

    @Test
    fun tombstone_roundtrip() {
        val (rec, json) = roundtrip(fixture3, "00000000-0000-4000-8000-000000000003")
        assertEquals(1713462000456L, rec.deletedAt)
        assertEquals("", rec.text)
        assertEquals(0L, rec.durationMs)
        assertEquals("", rec.provider)
        assertTrue(json.contains("\"deleted_at\":1713462000456"))
    }

    @Test
    fun agent_mode_roundtrip() {
        val (rec, _) = roundtrip(fixture4, "00000000-0000-4000-8000-000000000004")
        assertEquals("agent", rec.mode)
        assertEquals("llama-3.3-70b-versatile", rec.model)
        assertEquals(1713459000123L, rec.createdAt)
    }

    @Test
    fun malformed_json_rejected() {
        assertEquals(InvalidReason.MalformedJson, parseErr("{ not json".toByteArray(), "00000000-0000-4000-8000-000000000001"))
    }

    @Test
    fun invalid_utf8_rejected() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x01)
        assertEquals(InvalidReason.MalformedJson, parseErr(bytes, "00000000-0000-4000-8000-000000000001"))
    }

    @Test
    fun unknown_schema_version_rejected() {
        val rec = parseOk(fixture1, "00000000-0000-4000-8000-000000000001").copy(v = 2)
        assertEquals(InvalidReason.UnknownSchemaVersion, parseErr(rec.toJson().toByteArray(), "00000000-0000-4000-8000-000000000001"))
    }

    @Test
    fun filename_id_mismatch_rejected() {
        assertEquals(InvalidReason.IdNameMismatch, parseErr(fixture1, "00000000-0000-4000-8000-000000000002"))
    }

    @Test
    fun negative_deleted_at_rejected() {
        val rec = parseOk(fixture3, "00000000-0000-4000-8000-000000000003").copy(deletedAt = -5)
        assertEquals(InvalidReason.BadTimestamp, parseErr(rec.toJson().toByteArray(), "00000000-0000-4000-8000-000000000003"))
    }

    @Test
    fun non_integral_rejected() {
        val o = JSONObject()
        o.put("v", 1)
        o.put("id", "00000000-0000-4000-8000-000000000001")
        o.put("created_at", 1713456000123L)
        o.put("deleted_at", JSONObject.NULL)
        o.put("text", "x")
        o.put("mode", "transcription")
        o.put("duration_ms", 1.5)
        o.put("provider", "groq")
        o.put("model", JSONObject.NULL)
        o.put("language", JSONObject.NULL)
        assertEquals(InvalidReason.NonIntegral, parseErr(o.toString().toByteArray(), "00000000-0000-4000-8000-000000000001"))
    }

    @Test
    fun null_model_roundtrips_as_null() {
        val (_, json) = roundtrip(fixture2, "00000000-0000-4000-8000-000000000002")
        assertTrue("null must stay null", json.contains("\"model\":null"))
        assertTrue("null must stay null", json.contains("\"language\":null"))
    }

    @Test
    fun empty_string_model_distinct_from_null() {
        val rec = parseOk(fixture2, "00000000-0000-4000-8000-000000000002")
        assertNull(rec.model)
        val withEmpty = rec.copy(model = "")
        val reparsed = parseOk(withEmpty.toJson().toByteArray(), "00000000-0000-4000-8000-000000000002")
        assertEquals("", reparsed.model)
        assertNotEquals(null, reparsed.model)
    }

    @Test
    fun uuid_basename_accepts_valid_and_rejects_others() {
        assertEquals("00000000-0000-4000-8000-000000000001", uuidBasename("00000000-0000-4000-8000-000000000001.json"))
        assertNull(uuidBasename("X-copy.json"))
        assertNull(uuidBasename("00000000-0000-3000-8000-000000000001.json"))
        assertNull(uuidBasename("00000000-0000-4000-7000-000000000001.json"))
        assertNull(uuidBasename("00000000-0000-4000-8000-000000000001.txt"))
        assertNull(uuidBasename("00000000-0000-4000-8000-000000000001"))
    }

    @Test
    fun is_agent_mode_mapping_matches_wire_mode() {
        fun isAgentMode(mode: String): Boolean = mode == "agent"
        val live = parseOk(fixture1, "00000000-0000-4000-8000-000000000001")
        val agent = parseOk(fixture4, "00000000-0000-4000-8000-000000000004")
        assertFalse("transcription must map to isAgentMode=false", isAgentMode(live.mode))
        assertTrue("agent must map to isAgentMode=true", isAgentMode(agent.mode))
        assertEquals("transcription", if (isAgentMode(live.mode)) "agent" else "transcription")
        assertEquals("agent", if (isAgentMode(agent.mode)) "agent" else "transcription")
    }

    // ---------------------------------------------------------------------
    // Layer 1 — §30.5 record kinds (wire codec)
    // ---------------------------------------------------------------------

    private fun dictionaryRecord(
        id: String,
        spoken: String,
        corrected: String,
        kind: String,
        deletedAt: Long? = null,
    ): WireRecord = WireRecord(
        v = 1,
        id = id,
        createdAt = 1713465000123L,
        deletedAt = deletedAt,
        rtype = RecordType.Dictionary,
        spoken = spoken,
        corrected = corrected,
        kind = kind,
    )

    @Test
    fun dictionary_record_roundtrip() {
        val (rec, json) = roundtrip(fixture5, "00000000-0000-4000-8000-000000000005")
        assertEquals(RecordType.Dictionary, rec.rtype)
        assertEquals("gonna", rec.spoken)
        assertEquals("going to", rec.corrected)
        assertEquals("correction", rec.kind)
        assertEquals("", rec.text)
        assertNull(rec.model)
        assertEquals(
            rec.content(),
            RecordContent.Dictionary(
                DictionaryTuple(
                    createdAt = 1713465000123L,
                    spoken = "gonna",
                    corrected = "going to",
                    kind = "correction",
                )
            )
        )
        assertEquals(
            "dictionary record must serialize byte-identically to its fixture",
            String(fixture5).trimEnd(),
            json
        )
    }

    @Test
    fun snippet_record_roundtrip() {
        val (rec, json) = roundtrip(fixture6, "00000000-0000-4000-8000-000000000006")
        assertEquals(RecordType.Snippet, rec.rtype)
        assertEquals("addr", rec.trigger)
        assertEquals("123 Example Street, Springfield", rec.expansion)
        assertEquals(
            rec.content(),
            RecordContent.Snippet(
                SnippetTuple(
                    createdAt = 1713468000123L,
                    trigger = "addr",
                    expansion = "123 Example Street, Springfield",
                )
            )
        )
        assertEquals(
            "snippet record must serialize byte-identically to its fixture",
            String(fixture6).trimEnd(),
            json
        )
    }

    @Test
    fun settings_record_roundtrip() {
        val (rec, json) = roundtrip(fixture7, "00000000-0000-4000-8000-000000000007")
        assertEquals(RecordType.Settings, rec.rtype)
        assertEquals("snippets_enabled", rec.settingsKey)
        assertEquals("true", rec.settingsValue)
        assertEquals(
            rec.content(),
            RecordContent.Settings(
                SettingsTuple(
                    createdAt = 1713471000123L,
                    key = "snippets_enabled",
                    value = "true",
                )
            )
        )
        assertEquals(
            "settings record must serialize byte-identically to its fixture",
            String(fixture7).trimEnd(),
            json
        )
    }

    @Test
    fun unknown_type_rejected() {
        val rec = roundtrip(fixture1, "00000000-0000-4000-8000-000000000001").first
        val note = rec.toJson().replace("\"deleted_at\":null", "\"type\":\"note\",\"deleted_at\":null")
        assertEquals(
            InvalidReason.UnknownType,
            parseErr(note.toByteArray(), "00000000-0000-4000-8000-000000000001")
        )
        val numeric = rec.toJson().replace("\"deleted_at\":null", "\"type\":5,\"deleted_at\":null")
        assertEquals(
            InvalidReason.UnknownType,
            parseErr(numeric.toByteArray(), "00000000-0000-4000-8000-000000000001")
        )
    }

    @Test
    fun missing_dictionary_fields_rejected() {
        val withSpoken = dictionaryRecord("00000000-0000-4000-8000-000000000005", "gonna", "going to", "correction").toJson()
        val withoutSpoken = JSONObject(withSpoken).apply { remove("spoken") }
        assertEquals(
            InvalidReason.MissingTypeField,
            parseErr(withoutSpoken.toString().toByteArray(), "00000000-0000-4000-8000-000000000005")
        )

        val blank = dictionaryRecord("00000000-0000-4000-8000-000000000005", "gonna", "   ", "correction")
        assertEquals(
            InvalidReason.MissingTypeField,
            parseErr(blank.toJson().toByteArray(), "00000000-0000-4000-8000-000000000005")
        )

        val badKind = dictionaryRecord("00000000-0000-4000-8000-000000000005", "gonna", "going to", "synonym")
        assertEquals(
            InvalidReason.BadKind,
            parseErr(badKind.toJson().toByteArray(), "00000000-0000-4000-8000-000000000005")
        )
    }

    @Test
    fun missing_snippet_fields_rejected() {
        val rec = WireRecord(
            v = 1,
            id = "00000000-0000-4000-8000-000000000006",
            createdAt = 1713468000123L,
            deletedAt = null,
            rtype = RecordType.Snippet,
            trigger = "addr",
            expansion = "123 Example Street, Springfield",
        ).copy(expansion = null)
        assertEquals(
            InvalidReason.MissingTypeField,
            parseErr(rec.toJson().toByteArray(), "00000000-0000-4000-8000-000000000006")
        )
    }

    @Test
    fun missing_settings_key_rejected() {
        val o = JSONObject(
            """{"v":1,"id":"00000000-0000-4000-8000-000000000007","created_at":1713471000123,"type":"settings","key":"snippets_enabled","value":"true"}"""
        ).apply { remove("key") }
        assertEquals(
            InvalidReason.MissingTypeField,
            parseErr(o.toString().toByteArray(), "00000000-0000-4000-8000-000000000007")
        )
    }

    @Test
    fun explicit_history_type_roundtrips_byte_identical() {
        val compact = roundtrip(fixture1, "00000000-0000-4000-8000-000000000001").second
        val withType = compact.replace("\"deleted_at\":null", "\"type\":\"history\",\"deleted_at\":null")
        val rec = parseOk(withType.toByteArray(), "00000000-0000-4000-8000-000000000001")
        assertEquals(RecordType.History, rec.rtype)
        assertEquals(
            "history records must never serialize the type field",
            compact,
            rec.toJson()
        )
    }

    @Test
    fun tombstoned_dictionary_record_parses() {
        val rec = dictionaryRecord(
            "00000000-0000-4000-8000-000000000005",
            "gonna",
            "going to",
            "correction",
            deletedAt = 1713469000123L
        )
        val reparsed = parseOk(rec.toJson().toByteArray(), "00000000-0000-4000-8000-000000000005")
        assertEquals(1713469000123L, reparsed.deletedAt)
        assertEquals(RecordType.Dictionary, reparsed.rtype)
        val content = reparsed.content()
        assertTrue(content is RecordContent.Dictionary)
        assertEquals("gonna", (content as RecordContent.Dictionary).tuple.spoken)
    }

    @Test
    fun kinds_are_never_content_equal() {
        val h = parseOk(fixture1, "00000000-0000-4000-8000-000000000001").content()
        val d = parseOk(fixture5, "00000000-0000-4000-8000-000000000005").content()
        val s = parseOk(fixture6, "00000000-0000-4000-8000-000000000006").content()
        val st = parseOk(fixture7, "00000000-0000-4000-8000-000000000007").content()
        assertFalse(tuplesEqual(h, d))
        assertFalse(tuplesEqual(h, s))
        assertFalse(tuplesEqual(h, st))
        assertFalse(tuplesEqual(d, s))
        assertFalse(tuplesEqual(d, st))
        assertFalse(tuplesEqual(s, st))
        assertTrue(tuplesEqual(d, d))
    }
}