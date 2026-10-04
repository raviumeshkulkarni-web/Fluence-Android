package com.groq.voicetyper.sync.v1

import android.content.Context
import android.content.SharedPreferences

/**
 * In-memory [SharedPreferences] fake, plus a [Context] that hands it out by
 * file name.
 *
 * Deliberately not a mocking framework: the storage seam must round-trip real
 * `putString`/`getString` calls and honour `edit()` semantics, which is exactly
 * what a hand-written fake gives and what a relaxed mock silently does not.
 */
internal class FakePrefsStore {

    private val files = mutableMapOf<String, MutableMap<String, Any?>>()
    private val listeners = mutableMapOf<String, MutableList<SharedPreferences.OnSharedPreferenceChangeListener>>()

    /** Keys removed via `edit().clear()`, per file. */
    val cleared = mutableListOf<String>()

    fun fileOf(name: String): MutableMap<String, Any?> =
        files.getOrPut(name) { linkedMapOf() }

    fun names(): Set<String> = files.keys

    fun exists(name: String): Boolean = files.containsKey(name)

    inner class FakeEditor(private val file: String) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) =
            apply { pending[key] = values }

        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removals.add(key) }
        override fun clear() = apply { clearAll = true }

        override fun commit(): Boolean {
            val target = fileOf(file)
            if (clearAll) {
                cleared.add(file)
                target.clear()
                clearAll = false
            }
            removals.forEach { target.remove(it) }
            removals.clear()
            val changed = pending.keys.toList()
            target.putAll(pending)
            pending.clear()
            changed.forEach { k ->
                listeners[file]?.forEach { l ->
                    runCatching { l.onSharedPreferenceChanged(fake(file), k) }
                }
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }

    inner class FakeSharedPrefs(private val file: String) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = fileOf(file)

        override fun getString(key: String, defValue: String?): String? =
            fileOf(file)[key] as? String ?: defValue

        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            fileOf(file)[key] as? MutableSet<String> ?: defValues

        override fun getInt(key: String, defValue: Int): Int = fileOf(file)[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = fileOf(file)[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = fileOf(file)[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            fileOf(file)[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = fileOf(file).containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(file)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {
            listeners.getOrPut(file) { mutableListOf() }.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {
            listeners[file]?.remove(listener)
        }
    }

    private fun fake(file: String): SharedPreferences = FakeSharedPrefs(file)

    /**
     * [android.content.ContextWrapper] with a null base is the lightest way to
     * get a real `Context` that only overrides `getSharedPreferences`; the
     * member Android still requires (`getAssets`) is supplied trivially because
     * nothing under test touches it.
     */
    fun context(): Context = object : android.content.ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            fileOf(name)
            return FakeSharedPrefs(name)
        }

        override fun getAssets(): android.content.res.AssetManager? = null

        override fun getApplicationContext(): Context = this

        override fun getPackageName(): String = "fluence.test"
    }
}
