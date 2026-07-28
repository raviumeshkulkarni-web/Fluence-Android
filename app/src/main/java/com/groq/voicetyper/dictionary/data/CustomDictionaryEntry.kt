package com.groq.voicetyper.dictionary.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "custom_dictionary",
    indices = [Index(value = ["spokenText"], unique = true)]
)
data class CustomDictionaryEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The word or phrase spoken by user/STT (e.g. "fluence", "asap") */
    val spokenText: String,

    /** The replacement text (e.g. "Fluence", "ASAP") */
    val replacementText: String,

    /** Allows users to temporarily pause a rule without deleting it */
    val isEnabled: Boolean = true
)
