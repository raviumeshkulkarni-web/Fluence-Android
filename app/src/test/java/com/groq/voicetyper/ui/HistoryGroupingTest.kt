package com.groq.voicetyper.ui

import com.groq.voicetyper.history.TranscriptionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class HistoryGroupingTest {

    private fun entryAtDayOffset(id: Long, daysAgo: Int, text: String): TranscriptionEntry {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -daysAgo)
        }
        return TranscriptionEntry(
            id = id,
            text = text,
            provider = "groq",
            durationMs = 1500L,
            isAgentMode = false,
            timestamp = cal.timeInMillis
        )
    }

    @Test
    fun groupEntries_newestFirst_placesTodayFirstAndOlderDaysBelow() {
        val older = entryAtDayOffset(id = 1L, daysAgo = 10, text = "older")
        val yesterday = entryAtDayOffset(id = 2L, daysAgo = 1, text = "yesterday")
        val today = entryAtDayOffset(id = 3L, daysAgo = 0, text = "today")

        // Pass in mixed order (older, today, yesterday) to verify bucket sorting
        // does not depend on input order.
        val groups = groupEntries(listOf(older, today, yesterday), oldestFirst = false)

        assertEquals(3, groups.size)
        assertEquals("Today", groups[0].label)
        assertEquals("Yesterday", groups[1].label)
        assertEquals(listOf(today), groups[0].items)
        assertEquals(listOf(yesterday), groups[1].items)
        assertEquals(listOf(older), groups[2].items)
    }

    @Test
    fun groupEntries_oldestFirst_placesOldestDayFirstAndTodayLast() {
        val older = entryAtDayOffset(id = 1L, daysAgo = 10, text = "older")
        val yesterday = entryAtDayOffset(id = 2L, daysAgo = 1, text = "yesterday")
        val today = entryAtDayOffset(id = 3L, daysAgo = 0, text = "today")

        val groups = groupEntries(listOf(today, yesterday, older), oldestFirst = true)

        assertEquals(3, groups.size)
        assertEquals(listOf(older), groups[0].items)
        assertEquals("Yesterday", groups[1].label)
        assertEquals("Today", groups[2].label)
        assertEquals(listOf(today), groups[2].items)
    }

    @Test
    fun groupEntries_emptyInput_returnsEmptyList() {
        assertTrue(groupEntries(emptyList(), oldestFirst = false).isEmpty())
        assertTrue(groupEntries(emptyList(), oldestFirst = true).isEmpty())
    }
}
