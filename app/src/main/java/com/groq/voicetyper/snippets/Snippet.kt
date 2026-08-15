package com.groq.voicetyper.snippets

/**
 * A user-defined voice snippet.
 *
 * When the spoken [trigger] phrase appears in a transcript, it is replaced by
 * the [expansion] text. Enable/disable is controlled globally by the master
 * switch ([SnippetPreferences.isSnippetsEnabled]); there is no per-snippet
 * toggle in V1.
 *
 * §30 sync metadata: [uuid] is the wire identity (assigned at creation;
 * entries created before sync carry it already since v2 writes fresh UUIDs),
 * [createdAt]/[deletedAt] mirror the §6 tombstone rules, and
 * [syncState]/[serverFileId]/[syncAccount]/[quarantineReason] are the §6-table
 * shadow of the Windows `snippets.json` metadata. User-facing reads filter
 * [deletedAt] == null (spec §30.4).
 */
data class Snippet(
    val id: Long,
    val trigger: String,
    val expansion: String,
    val uuid: String? = null,
    val createdAt: Long? = null,
    val deletedAt: Long? = null,
    val syncState: String? = null,
    val serverFileId: String? = null,
    val syncAccount: String? = null,
    val quarantineReason: String? = null
)