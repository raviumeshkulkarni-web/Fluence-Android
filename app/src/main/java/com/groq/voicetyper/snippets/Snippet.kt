package com.groq.voicetyper.snippets

/**
 * A user-defined voice snippet.
 *
 * When the spoken [trigger] phrase appears in a transcript, it is replaced by
 * the [expansion] text. Enable/disable is controlled globally by the master
 * switch ([SnippetPreferences.isSnippetsEnabled]); there is no per-snippet
 * toggle.
 *
 * Frozen v1.2 sync metadata: [uuid] is the wire identity, [createdAt] is the
 * LWW timestamp baseline and [updatedAt] bumps on every edit (winner =
 * max(updatedAt, deviceId); tombstones are ordinary records). [deviceId],
 * [dirty] and [everPushed] drive change detection; [deletedAt] marks a
 * tombstone. Legacy legacy-engine columns ([syncState]/[serverFileId]/
 * [syncAccount]/[quarantineReason]) remain for file-format compatibility and
 * are no longer written by the v1.2 path. User-facing reads filter
 * [deletedAt] == null.
 */
data class Snippet(
    val id: Long,
    val trigger: String,
    val expansion: String,
    val uuid: String? = null,
    val createdAt: Long? = null,
    val deletedAt: Long? = null,
    val updatedAt: Long? = null,
    val deviceId: String? = null,
    val isEnabled: Boolean = true,
    val dirty: Boolean = false,
    val everPushed: Boolean = false,
    // Dormant legacy columns (kept for document compatibility).
    val syncState: String? = null,
    val serverFileId: String? = null,
    val syncAccount: String? = null,
    val quarantineReason: String? = null
) {
    /** Wire identity (v1.2 name for the legacy [uuid] field). */
    fun effectiveSyncId(): String? = uuid

    /** Canonical business key — always derived from content. */
    fun businessKey(): String = trigger.trim().lowercase()
}