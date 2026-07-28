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
    indices = [Index(value = ["spokenText", "correctedText"], unique = true)]
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
