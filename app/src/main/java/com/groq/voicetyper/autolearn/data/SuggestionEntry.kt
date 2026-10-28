package com.groq.voicetyper.autolearn.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SuggestionStatus {
    PENDING,
    ACCEPTED,
    DISMISSED
}

@Entity(
    tableName = "suggestion_history",
    indices = [
        Index(value = ["spokenText", "correctedText"], unique = true),
        // Pending-list read path (WHERE status ORDER BY frequency, lastSeenAt)
        // is indexed the same way transcription_history was (MIGRATION_13_14):
        // dismissals are retained for deny memory, so the table is
        // monotonic and an unindexed scan+sort degrades the UI.
        Index(value = ["status", "frequency", "lastSeenAt"])
    ]
)
data class SuggestionEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** What the STT originally produced */
    val spokenText: String,

    /** What the user edited it to */
    val correctedText: String,

    /** Observed occurrence frequency count */
    val frequency: Int = 1,

    /** Status in the human review lifecycle */
    val status: SuggestionStatus = SuggestionStatus.PENDING,

    /** Timestamp when first seen */
    val createdAt: Long = System.currentTimeMillis(),

    /** Timestamp when last updated */
    val lastSeenAt: Long = System.currentTimeMillis()
)
