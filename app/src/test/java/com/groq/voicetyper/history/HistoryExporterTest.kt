package com.groq.voicetyper.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class HistoryExporterTest {

    private fun entry(
        id: Long = 1,
        text: String = "hello world",
        provider: String = "groq",
        isAgentMode: Boolean = false,
        durationMs: Long = 1_500,
        timestamp: Long = 1_725_000_000_000,
        language: String? = "en",
        model: String? = "whisper-large-v3"
    ): TranscriptionEntry = TranscriptionEntry(
        id = id,
        text = text,
        provider = provider,
        model = model,
        language = language,
        durationMs = durationMs,
        isAgentMode = isAgentMode,
        timestamp = timestamp
    )

    @Test
    fun txt_matches_windows_layout() {
        val out = HistoryExporter.toTxt(listOf(entry(), entry(id = 2, text = "second", timestamp = 1_725_000_060_000)))
        assertEquals(
            "1725000000000\nhello world\n\n1725000060000\nsecond\n",
            out
        )
    }

    @Test
    fun txt_empty_export_is_single_newline() {
        assertEquals("\n", HistoryExporter.toTxt(emptyList()))
    }

    @Test
    fun csv_has_header_and_quotes_every_cell() {
        val out = HistoryExporter.toCsv(listOf(entry()))
        val lines = out.split("\r\n")
        assertEquals(
            "\"timestamp\",\"text\",\"provider\",\"mode\",\"duration_ms\",\"language\",\"model\"",
            lines[0]
        )
        assertEquals(
            "\"1725000000000\",\"hello world\",\"groq\",\"transcription\",\"1500\",\"en\",\"whisper-large-v3\"",
            lines[1]
        )
        assertTrue("rows must end with CRLF", out.endsWith("\r\n"))
    }

    @Test
    fun csv_escapes_quotes_and_guards_formulas() {
        val out = HistoryExporter.toCsv(
            listOf(
                entry(text = "=SUM(\"A1\")", isAgentMode = true, language = null, model = null),
                entry(id = 2, text = "   =SUM(\"A1\")", isAgentMode = true, language = null, model = null),
                entry(id = 3, text = "  +123", isAgentMode = true, language = null, model = null),
                entry(id = 4, text = " -123", isAgentMode = true, language = null, model = null),
                entry(id = 5, text = "   @EVIL", isAgentMode = true, language = null, model = null),
                entry(id = 6, text = "\tcmd", isAgentMode = false, language = null, model = null),
                entry(id = 7, text = "\rcmd", isAgentMode = false, language = null, model = null)
            )
        )
        assertTrue(out.contains("\"'=SUM(\"\"A1\"\")\""))
        assertTrue(out.contains("\"'   =SUM(\"\"A1\"\")\""))
        assertTrue(out.contains("\"'  +123\""))
        assertTrue(out.contains("\"' -123\""))
        assertTrue(out.contains("\"'   @EVIL\""))
        assertTrue(out.contains("\"'\tcmd\""))
        assertTrue(out.contains("\"'\rcmd\""))
        assertTrue(out.contains("\"agent\""))
        assertTrue(out.contains(",\"\",\"\""))
    }

    @Test
    fun json_escapes_control_characters_and_uses_nulls() {
        val out = HistoryExporter.toJson(
            listOf(entry(text = "line1\n\"quoted\"\t\\end", language = null, model = null))
        )
        assertTrue(out.contains("\"text\": \"line1\\n\\\"quoted\\\"\\t\\\\end\""))
        assertTrue(out.contains("\"language\": null"))
        assertTrue(out.contains("\"model\": null"))
        assertTrue(out.contains("\"mode\": \"transcription\""))
        assertTrue(out.startsWith("[\n"))
        assertTrue(out.endsWith("]\n"))
    }

    @Test
    fun json_separates_multiple_entries_with_comma() {
        val out = HistoryExporter.toJson(listOf(entry(), entry(id = 2)))
        assertTrue(out.contains("  },\n  {"))
        assertTrue(out.contains("\"id\": 2"))
    }

    @Test
    fun fileName_uses_local_stamp_and_extension() {
        val date = Date(0L)
        assertTrue(HistoryExporter.fileName(ExportFormat.TXT, date).startsWith("fluence-history-"))
        assertTrue(HistoryExporter.fileName(ExportFormat.TXT, date).endsWith(".txt"))
        assertTrue(HistoryExporter.fileName(ExportFormat.CSV, date).endsWith(".csv"))
        assertTrue(HistoryExporter.fileName(ExportFormat.JSON, date).endsWith(".json"))
    }

    @Test
    fun content_routes_each_format() {
        val entries = listOf(entry())
        assertEquals(HistoryExporter.toTxt(entries), HistoryExporter.content(ExportFormat.TXT, entries))
        assertEquals(HistoryExporter.toCsv(entries), HistoryExporter.content(ExportFormat.CSV, entries))
        assertEquals(HistoryExporter.toJson(entries), HistoryExporter.content(ExportFormat.JSON, entries))
    }
}
