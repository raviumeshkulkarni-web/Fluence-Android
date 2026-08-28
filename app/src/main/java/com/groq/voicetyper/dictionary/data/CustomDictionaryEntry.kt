package com.groq.voicetyper.dictionary.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "custom_dictionary",
    indices = [
        // User-facing uniqueness is account-scoped. Foreign-account rows are
        // retained locally so switching back does not require re-downloading.
        Index(value = ["spokenText", "syncAccount"], unique = true),
        // One sync identity per row (§5-style; mirrors transcription_history).
        Index(value = ["syncId"], unique = true)
    ]
)
data class CustomDictionaryEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The word or phrase spoken by user/STT (e.g. "fluence", "asap") */
    val spokenText: String,

    /** The replacement text (e.g. "Fluence", "ASAP") */
    val replacementText: String,

    /** Allows users to temporarily pause a rule without deleting it */
    val isEnabled: Boolean = true,

    // §30.4 sync columns (migration 5 -> 6, non-destructive). `syncId` is the
    // wire UUID, assigned lazily like `transcription_history.syncId`; the
    // other columns mirror the §6 sync_state table shadow of the Windows
    // dictionary.json metadata.
    val syncId: String? = null,

    val createdAt: Long? = null,

    val deletedAt: Long? = null,

    // Frozen v1.2 LWW metadata (migration 10 -> 11): updatedAt bumps on every
    // edit; winner = max(updatedAt, deviceId); tombstones are ordinary records.
    val updatedAt: Long? = null,

    val deviceId: String? = null,

    val dirty: Boolean = false,

    val everPushed: Boolean = false,

    @ColumnInfo(defaultValue = "local")
    val syncState: String = "local",

    val serverFileId: String? = null,

    val syncAccount: String? = null,

    val quarantineReason: String? = null
)
