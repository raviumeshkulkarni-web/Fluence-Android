package com.groq.voicetyper.sync.engine

/**
 * Incremental sync cache (§31): maps Drive fileId → (md5 token, canonical record JSON).
 * Used to avoid re-downloading unchanged files when the listing's md5 matches.
 * In-memory only for now; persistence can be added without changing the interface.
 */
interface FileCacheStore {
    fun all(): Map<String, CachedRow>
    fun put(fileId: String, md5: String?, recordJson: String)
    fun remove(fileId: String)
    fun prune(activeFileIds: Set<String>)
}

data class CachedRow(
    val md5: String?,
    val recordJson: String
)

/** No-op / in-memory implementation for production wiring. */
class InMemoryFileCacheStore : FileCacheStore {
    private val map = mutableMapOf<String, CachedRow>()
    val entries: MutableMap<String, CachedRow> get() = map
    override fun all(): Map<String, CachedRow> = map.toMap()
    override fun put(fileId: String, md5: String?, recordJson: String) { map[fileId] = CachedRow(md5, recordJson) }
    override fun remove(fileId: String) { map.remove(fileId) }
    override fun prune(activeFileIds: Set<String>) { map.keys.retainAll(activeFileIds) }
}

/** Alias for test compatibility */
typealias FakeCache = InMemoryFileCacheStore
