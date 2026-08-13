package com.groq.voicetyper.snippets

/**
 * A user-defined voice snippet.
 *
 * When the spoken [trigger] phrase appears in a transcript, it is replaced by
 * the [expansion] text. Enable/disable is controlled globally by the master
 * switch ([SnippetPreferences.isSnippetsEnabled]); there is no per-snippet
 * toggle in V1.
 */
data class Snippet(
    val id: Long,
    val trigger: String,
    val expansion: String
)