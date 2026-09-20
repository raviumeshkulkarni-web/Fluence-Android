package com.groq.voicetyper.cleanup

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * AI cleanup styles: three built in jobs plus unlimited user custom styles.
 *
 * Built ins are fixed ids with hidden prompts (see CleanupProcessor):
 * proofread, natural, professional. Customs are user named plus a 1000 char
 * hint, stored locally in fluence_prefs. One app lives in one AI style only,
 * tracked separately from the deterministic bucket map, so an app can hold
 * one deterministic bucket plus one AI style at the same time.
 */
object AiCleanupPreferences {
    private const val PREFS_NAME = "fluence_prefs"
    private const val KEY_AI_OVERRIDES = "ai_cleanup_package_overrides"
    private const val KEY_CUSTOM_STYLES = "ai_cleanup_custom_styles"

    const val MAX_STYLE_NAME_LENGTH = 30
    const val MAX_STYLE_HINT_LENGTH = 1000

    const val ID_PROOFREAD = "proofread"
    const val ID_NATURAL = "natural"
    const val ID_PROFESSIONAL = "professional"

    val BUILT_IN_IDS = listOf(ID_PROOFREAD, ID_NATURAL, ID_PROFESSIONAL)

    data class CustomStyle(
        val id: String,
        val name: String,
        val hint: String
    )

    data class BuiltInMeta(
        val id: String,
        val title: String,
        val explanation: String,
        val exampleIn: String,
        val exampleOut: String
    )

    fun builtInMeta(id: String): BuiltInMeta = when (id) {
        ID_NATURAL -> BuiltInMeta(
            id = ID_NATURAL,
            title = "Natural",
            explanation = "Turns rambling speech into a short, natural chat message. Keeps names and numbers exact.",
            exampleIn = "like are you free tomorrow evening time let me know if that works for you",
            exampleOut = "free tomorrow evening. let me know if that works"
        )
        ID_PROFESSIONAL -> BuiltInMeta(
            id = ID_PROFESSIONAL,
            title = "Professional",
            explanation = "Rewrites speech as a polite, clear work message. Never adds new facts.",
            exampleIn = "yeah tell boss project delayed need two more days will update soon",
            exampleOut = "Hi. Update on the project. We need two more days. Will keep you posted."
        )
        else -> BuiltInMeta(
            id = ID_PROOFREAD,
            title = "Proofread",
            explanation = "Fixes grammar and removes filler words like um. Keeps everything you said.",
            exampleIn = "hello world. hello again um please send report",
            exampleOut = "Hello world. Hello again. Please send report."
        )
    }

    fun styleTitle(context: Context, styleId: String): String {
        if (styleId in BUILT_IN_IDS) return builtInMeta(styleId).title
        return loadCustomStyles(context).firstOrNull { it.id == styleId }?.name ?: "Custom style"
    }

    fun isBuiltIn(styleId: String): Boolean = styleId in BUILT_IN_IDS

    fun isKnownStyle(context: Context, styleId: String): Boolean {
        if (styleId in BUILT_IN_IDS) return true
        return loadCustomStyles(context).any { it.id == styleId }
    }

    // ── App overrides: package -> styleId, one AI style per app ──

    fun getOverrides(context: Context): Map<String, String> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            decodeEntries(prefs.getStringSet(KEY_AI_OVERRIDES, emptySet()).orEmpty())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun setOverride(context: Context, packageName: String, styleId: String?) {
        if (packageName.isBlank()) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val updated = getOverrides(context).toMutableMap()
            if (styleId.isNullOrBlank() || !isKnownStyle(context, styleId)) {
                updated.remove(packageName)
            } else {
                updated[packageName] = styleId
            }
            prefs.edit().putStringSet(KEY_AI_OVERRIDES, encodeMap(updated)).apply()
        } catch (_: Exception) {
        }
    }

    fun styleForPackage(context: Context, packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        val id = getOverrides(context)[packageName] ?: return null
        return if (isKnownStyle(context, id)) id else null
    }

    fun appCountForStyle(context: Context, styleId: String): Int {
        return getOverrides(context).count { it.value == styleId }
    }

    // ── Custom styles ──

    fun loadCustomStyles(context: Context): List<CustomStyle> {
        return try {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CUSTOM_STYLES, "") ?: ""
            if (raw.isBlank()) return emptyList()
            val arr = JSONArray(raw)
            val out = mutableListOf<CustomStyle>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val name = o.optString("name").trim()
                val hint = o.optString("hint", "")
                if (id.isBlank() || name.isBlank()) continue
                out.add(CustomStyle(id = id, name = name, hint = hint))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCustomStyle(context: Context, name: String, hint: String, id: String? = null): CustomStyle? {
        val cleanName = name.trim().take(MAX_STYLE_NAME_LENGTH).trim()
        if (cleanName.isEmpty()) return null
        val cleanHint = CleanupProcessor.sanitizeCustomPrompt(hint)
        if (cleanHint.isEmpty()) return null
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = loadCustomStyles(context).toMutableList()
            if (id != null) {
                val idx = current.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    current[idx] = CustomStyle(id = id, name = cleanName, hint = cleanHint)
                } else {
                    current.add(CustomStyle(id = id, name = cleanName, hint = cleanHint))
                }
                writeCustomStyles(prefs, current)
                return current.first { it.id == id }
            }
            val created = CustomStyle(id = "custom:" + UUID.randomUUID().toString(), name = cleanName, hint = cleanHint)
            current.add(created)
            writeCustomStyles(prefs, current)
            created
        } catch (_: Exception) {
            null
        }
    }

    fun deleteCustomStyle(context: Context, id: String) {
        if (isBuiltIn(id)) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            writeCustomStyles(prefs, loadCustomStyles(context).filter { it.id != id })
            // Apps in the deleted style return to Auto.
            val updated = getOverrides(context).toMutableMap()
            var changed = false
            for ((pkg, style) in updated.toMap()) {
                if (style == id) {
                    updated.remove(pkg)
                    changed = true
                }
            }
            if (changed) prefs.edit().putStringSet(KEY_AI_OVERRIDES, encodeMap(updated)).apply()
        } catch (_: Exception) {
        }
    }

    private fun writeCustomStyles(prefs: SharedPreferences, styles: List<CustomStyle>) {
        val arr = JSONArray()
        for (s in styles) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("hint", s.hint)
            })
        }
        prefs.edit().putString(KEY_CUSTOM_STYLES, arr.toString()).apply()
    }

    private fun decodeEntries(entries: Set<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (entry in entries) {
            val separator = entry.lastIndexOf(':')
            if (separator <= 0 || separator >= entry.length - 1) continue
            // Package names never contain a colon, style ids may (custom:uuid),
            // so split on the first colon for the package part.
            val first = entry.indexOf(':')
            if (first <= 0 || first >= entry.length - 1) continue
            val key = entry.substring(0, first)
            val value = entry.substring(first + 1)
            if (key.isNotBlank() && value.isNotBlank()) result[key] = value
        }
        return result
    }

    private fun encodeMap(map: Map<String, String>): Set<String> {
        return map.map { (key, value) -> "$key:$value" }.toSet()
    }

    /** Test-only helper to write overrides without a Context. */
    internal fun encodeMapForTests(map: Map<String, String>): Set<String> = encodeMap(map)

    /** Test-only helper to read overrides without a Context. */
    internal fun decodeEntriesForTests(entries: Set<String>): Map<String, String> = decodeEntries(entries)
}
