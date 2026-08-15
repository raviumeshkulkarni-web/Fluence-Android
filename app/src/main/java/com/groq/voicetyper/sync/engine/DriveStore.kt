package com.groq.voicetyper.sync.engine

import com.groq.voicetyper.sync.wire.WireRecord

interface DriveStore {
    fun findOrCreateFolder()

    fun listFiles(): List<FileMeta>

    fun getContent(fileId: String): ByteArray?

    fun createFile(name: String, record: WireRecord): String

    fun updateContent(fileId: String, record: WireRecord)
}
