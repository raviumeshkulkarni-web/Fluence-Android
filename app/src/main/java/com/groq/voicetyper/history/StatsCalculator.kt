package com.groq.voicetyper.history

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object StatsCalculator {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    fun wordCountOf(text: String): Int {
        var count = 0
        var inWord = false
        for (c in text) {
            if (c.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                count++
            }
        }
        return count
    }

    fun utcDateOf(timestampMs: Long): String {
        val cal = Calendar.getInstance(utc)
        cal.timeInMillis = timestampMs
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun utcWeekStartMs(nowMs: Long): Long {
        val cal = Calendar.getInstance(utc)
        cal.timeInMillis = nowMs
        val daysSinceMonday = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        cal.add(Calendar.DAY_OF_MONTH, -daysSinceMonday)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun utcMonthStartMs(nowMs: Long): Long {
        val cal = Calendar.getInstance(utc)
        cal.timeInMillis = nowMs
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun dailyAggregates(rows: List<TranscriptionEntry>): List<DailyStat> {
        val perDay = LinkedHashMap<String, LongArray>()
        for (row in rows) {
            val day = utcDateOf(row.timestamp)
            val agg = perDay.getOrPut(day) { longArrayOf(0L, 0L, 0L, 0L) }
            agg[0] = agg[0] + wordCountOf(row.text).toLong()
            agg[1] = agg[1] + 1L
            agg[2] = agg[2] + row.text.length.toLong()
            agg[3] = agg[3] + row.durationMs
        }
        return perDay.map { (day, agg) ->
            DailyStat(day = day, wordCount = agg[0], count = agg[1], chars = agg[2], dictationMs = agg[3])
        }
    }
}
