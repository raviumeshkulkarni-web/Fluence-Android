package com.groq.voicetyper.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_meta")
data class DeviceMetaEntry(
    @PrimaryKey val key: String,
    val value: String
)
