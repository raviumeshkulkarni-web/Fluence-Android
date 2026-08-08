package com.groq.voicetyper.history

import java.util.Calendar
import java.util.Locale

object StatsCalculator {
    private val splitRegex = Regex("\\s+")

    fun wordCountOf(text: String): Int =
        text.split(splitRegex).filter { it.isNotBlank() }.size

    fun effectiveDurationMs(text: String, durationMs: Long): Long {
        if (durationMs > 0L) return durationMs
        val wordCount = wordCountOf(text)
        if (wordCount == 0) return 0L
        return ((wordCount / 140.0) * 60_000.0).toLong().coerceAtLeast(1_000L)
    }

    fun localDateOf(timestampMs: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestampMs
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }
}
