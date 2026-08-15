package com.groq.voicetyper.sync.engine

interface LocalStore {
    fun listRows(account: String?): List<LocalRow>

    fun findRow(uuid: String): LocalRow?

    fun import(row: LocalRow)

    fun markTombstoned(uuid: String, deletedAt: Long)

    fun setServerFileId(uuid: String, fileId: String)

    fun setSyncState(uuid: String, state: String)

    fun quarantine(uuid: String, reason: QuarantineReason)

    fun clearQuarantine(uuid: String)

    fun hardDelete(uuid: String)
}

interface TokenProvider {
    fun hasValidToken(): Boolean
}
