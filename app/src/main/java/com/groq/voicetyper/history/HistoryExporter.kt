package com.groq.voicetyper.history

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Export formats mirror the Windows History export dialog. */
enum class ExportFormat(val extension: String, val mime: String) {
    TXT("txt", "text/plain"),
    CSV("csv", "text/csv"),
    JSON("json", "application/json")
}

/**
 * Pure History export builders, mirrored from the Windows client
 * (`web/src/lib/historyExport.ts` + HistoryExportDialog). Kept free of
 * Android types so formatting is unit-testable on the JVM.
 */
object HistoryExporter {

    fun content(format: ExportFormat, entries: List<TranscriptionEntry>): String =
        when (format) {
            ExportFormat.TXT -> toTxt(entries)
            ExportFormat.CSV -> toCsv(entries)
            ExportFormat.JSON -> toJson(entries)
        }

    /** Windows `historyTxt` layout parity: `timestamp\n text`, blank-line separated. */
    fun toTxt(entries: List<TranscriptionEntry>): String =
        entries.joinToString(separator = "\n\n") { "${it.timestamp}\n${it.text}" } + "\n"

    /** Windows `historyCsv` parity, including the formula-injection guard. */
    fun toCsv(entries: List<TranscriptionEntry>): String {
        val header = listOf(
            "timestamp",
            "text",
            "provider",
            "mode",
            "duration_ms",
            "language",
            "model"
        ).joinToString(",") { csvCell(it) }
        val rows = entries.map { entry ->
            listOf(
                entry.timestamp.toString(),
                entry.text,
                entry.provider,
                modeOf(entry),
                entry.durationMs.toString(),
                entry.language ?: "",
                entry.model ?: ""
            ).joinToString(",") { csvCell(it) }
        }
        return (listOf(header) + rows).joinToString("\r\n") + "\r\n"
    }

    /** Stable JSON array; hand-rolled so the file has no Android-only deps. */
    fun toJson(entries: List<TranscriptionEntry>): String {
        val sb = StringBuilder()
        sb.append("[\n")
        entries.forEachIndexed { index, entry ->
            sb.append("  {\n")
            sb.append("    \"id\": ").append(entry.id).append(",\n")
            sb.append("    \"timestamp\": ").append(entry.timestamp).append(",\n")
            sb.append("    \"text\": ").append(jsonString(entry.text)).append(",\n")
            sb.append("    \"provider\": ").append(jsonString(entry.provider)).append(",\n")
            sb.append("    \"mode\": ").append(jsonString(modeOf(entry))).append(",\n")
            sb.append("    \"duration_ms\": ").append(entry.durationMs).append(",\n")
            sb.append("    \"language\": ").append(entry.language?.let(::jsonString) ?: "null").append(",\n")
            sb.append("    \"model\": ").append(entry.model?.let(::jsonString) ?: "null").append("\n")
            sb.append("  }").append(if (index == entries.lastIndex) "" else ",").append("\n")
        }
        sb.append("]\n")
        return sb.toString()
    }

    /** Local-time filename stamp (Windows `localStamp` parity). */
    fun fileName(format: ExportFormat, date: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(date)
        return "fluence-history-$stamp.${format.extension}"
    }

    private fun modeOf(entry: TranscriptionEntry): String =
        if (entry.isAgentMode) "agent" else "transcription"

    private fun csvCell(value: String): String {
        var text = value
        // Spreadsheet apps treat these leading characters as formulas.
        val hasLeadingFormulaChar = text.isNotEmpty() && text[0] in charArrayOf('=', '+', '-', '@', '\t', '\r')
        val hasWhitespacePrefixedFormula = text.trimStart().let { trimmed ->
            trimmed.isNotEmpty() && trimmed[0] in charArrayOf('=', '+', '-', '@')
        }
        if (hasLeadingFormulaChar || hasWhitespacePrefixedFormula) {
            text = "'$text"
        }
        return "\"${text.replace("\"", "\"\"")}\""
    }

    private fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
