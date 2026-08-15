package com.groq.voicetyper.snippets

import android.content.Context
import android.content.SharedPreferences
import com.groq.voicetyper.dictionary.CompiledDictionaryRule
import com.groq.voicetyper.dictionary.DictionaryPreferences
import com.groq.voicetyper.dictionary.DictionaryRepository
import com.groq.voicetyper.dictionary.DictionaryTextPostProcessor
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Deterministic unit tests for the Voice Snippets feature.
 *
 * The pure matcher ([SnippetProcessor.expand]) is tested without any Android
 * dependency. The guarded facade ([SnippetProcessor.process]) and the
 * dictionary-snippet ordering seam are tested with mocked preferences.
 * Storage is tested against a small in-memory SharedPreferences fake because
 * the JVM unit-test environment has no real preference store.
 */
class SnippetProcessorTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── expand(): exact replacement ──────────────────────────────────────────

    @Test
    fun `exact trigger replacement`() {
        val text = "Please send me my linkedin"
        val snippets = listOf(Snippet(1, "my linkedin", "https://linkedin.com/in/u"))
        assertEquals("Please send me https://linkedin.com/in/u", SnippetProcessor.expand(text, snippets))
    }

    @Test
    fun `matching is case insensitive`() {
        val snippets = listOf(Snippet(1, "my linkedin", "url"))
        assertEquals("url", SnippetProcessor.expand("MY LINKEDIN", snippets))
        assertEquals("url", SnippetProcessor.expand("My LinkedIn", snippets))
    }

    @Test
    fun `trigger at beginning of transcript`() {
        val snippets = listOf(Snippet(1, "my linkedin", "url"))
        assertEquals("url is where i work", SnippetProcessor.expand("my linkedin is where i work", snippets))
    }

    @Test
    fun `trigger at end of transcript`() {
        val snippets = listOf(Snippet(1, "my linkedin", "url"))
        assertEquals("check url", SnippetProcessor.expand("check my linkedin", snippets))
    }

    @Test
    fun `trigger surrounded by punctuation`() {
        val snippets = listOf(Snippet(1, "my linkedin", "url"))
        assertEquals("(url) ok", SnippetProcessor.expand("(my linkedin) ok", snippets))
        assertEquals("send url!", SnippetProcessor.expand("send my linkedin!", snippets))
        assertEquals("the url.", SnippetProcessor.expand("the my linkedin.", snippets))
        assertEquals("\"url\"", SnippetProcessor.expand("\"my linkedin\"", snippets))
    }

    @Test
    fun `multiple snippets in one transcript`() {
        val snippets = listOf(
            Snippet(1, "my linkedin", "https://in"),
            Snippet(2, "my github", "https://gh")
        )
        assertEquals(
            "https://in and https://gh",
            SnippetProcessor.expand("my linkedin and my github", snippets)
        )
    }

    @Test
    fun `multiple occurrences of the same trigger`() {
        val snippets = listOf(Snippet(1, "my linkedin", "url"))
        assertEquals("url and url", SnippetProcessor.expand("my linkedin and my linkedin", snippets))
    }

    @Test
    fun `longest matching trigger wins`() {
        val snippets = listOf(
            Snippet(1, "my linkedin", "https://in"),
            Snippet(2, "my linkedin profile", "https://in/profile")
        )
        assertEquals(
            "https://in/profile is mine",
            SnippetProcessor.expand("my linkedin profile is mine", snippets)
        )
    }

    @Test
    fun `overlapping triggers never corrupt the transcript`() {
        val snippets = listOf(
            Snippet(1, "abc", "X"),
            Snippet(2, "bc", "Y")
        )
        assertEquals("X", SnippetProcessor.expand("abc", snippets))
    }

    @Test
    fun `partial word matches are rejected`() {
        val snippets = listOf(Snippet(1, "cat", "dog"))
        assertEquals(
            "The dog is in the concatenate category.",
            SnippetProcessor.expand("The cat is in the concatenate category.", snippets)
        )
        assertEquals("linkedins", SnippetProcessor.expand("linkedins", listOf(Snippet(1, "linkedin", "url"))))
    }

    @Test
    fun `invalid snippets are skipped silently`() {
        val snippets = listOf(
            Snippet(1, "", "url"),
            Snippet(2, "   ", "url"),
            Snippet(3, "my linkedin", ""),
            Snippet(4, " my linkedin ", "url")
        )
        assertEquals("go url", SnippetProcessor.expand("go my linkedin", snippets))
    }

    @Test
    fun `unicode triggers and expansions match case insensitively`() {
        val snippets = listOf(Snippet(1, "извините", "sorry"))
        assertEquals("sorry, пожалуйста", SnippetProcessor.expand("Извините, пожалуйста", snippets))
        assertEquals("sorry", SnippetProcessor.expand("ИЗВИНИТЕ", snippets))
    }

    @Test
    fun `astral plane characters are valid boundaries`() {
        val snippets = listOf(Snippet(1, "my linkedin", "url"))
        assertEquals("\uD83D\uDC4Durl", SnippetProcessor.expand("\uD83D\uDC4Dmy linkedin", snippets))
        assertEquals("url\uD83D\uDC4D", SnippetProcessor.expand("my linkedin\uD83D\uDC4D", snippets))
    }

    @Test
    fun `expansion text is never rescanned`() {
        val snippets = listOf(
            Snippet(1, "ping", "pong"),
            Snippet(2, "pong", "done")
        )
        assertEquals("pong", SnippetProcessor.expand("ping", snippets))
    }

    @Test
    fun `surrounding whitespace is preserved`() {
        val snippets = listOf(Snippet(1, "my linkedin", "url"))
        assertEquals("  url  ", SnippetProcessor.expand("  my linkedin  ", snippets))
    }

    @Test
    fun `blank input or empty snippets pass through`() {
        assertEquals("", SnippetProcessor.expand("", listOf(Snippet(1, "a", "b"))))
        assertEquals("hello", SnippetProcessor.expand("hello", emptyList()))
    }

    // ── process(): guarded facade ───────────────────────────────────────────

    @Test
    fun `disabled feature returns the transcript byte identical`() {
        mockkObject(SnippetPreferences)
        every { SnippetPreferences.isSnippetsEnabled(any()) } returns false
        every { SnippetPreferences.loadSnippets(any()) } throws IllegalStateException("must not be read")
        val context = mockk<Context>(relaxed = true)
        val text = "Please send me my linkedin"
        assertSame(text, SnippetProcessor.process(context, text))
    }

    @Test
    fun `enabled feature expands the transcript`() {
        mockkObject(SnippetPreferences)
        every { SnippetPreferences.isSnippetsEnabled(any()) } returns true
        every { SnippetPreferences.loadSnippets(any()) } returns listOf(Snippet(1, "my linkedin", "https://in"))
        val context = mockk<Context>(relaxed = true)
        assertEquals(
            "Please send me https://in",
            SnippetProcessor.process(context, "Please send me my linkedin")
        )
    }

    @Test
    fun `storage failure returns the original transcript`() {
        mockkObject(SnippetPreferences)
        every { SnippetPreferences.isSnippetsEnabled(any()) } returns true
        every { SnippetPreferences.loadSnippets(any()) } throws RuntimeException("prefs corrupted")
        val context = mockk<Context>(relaxed = true)
        val text = "Please send me my linkedin"
        assertSame(text, SnippetProcessor.process(context, text))
    }

    @Test
    fun `blank transcript skips all processing`() {
        mockkObject(SnippetPreferences)
        every { SnippetPreferences.isSnippetsEnabled(any()) } returns false
        val context = mockk<Context>(relaxed = true)
        assertSame("", SnippetProcessor.process(context, ""))
    }

    // ── Seam: dictionary then snippets, still one deliverable transcript ────

    @Test
    fun `dictionary output is expanded by snippets`() {
        mockkObject(DictionaryPreferences)
        every { DictionaryPreferences.isDictionaryEnabled(any()) } returns true
        mockkObject(DictionaryRepository)
        every { DictionaryRepository.getCompiledRules(any()) } returns
            listOf(CompiledDictionaryRule(regex = Regex("(?i)\\bfluence\\b"), replacementText = "Fluence"))
        mockkObject(SnippetPreferences)
        every { SnippetPreferences.isSnippetsEnabled(any()) } returns true
        every { SnippetPreferences.loadSnippets(any()) } returns listOf(Snippet(1, "fluence", "fluence.ai"))
        val context = mockk<Context>(relaxed = true)

        assertEquals(
            "visit fluence.ai",
            DictionaryTextPostProcessor.process(context, "visit fluence")
        )
    }

    @Test
    fun `snippets still apply when dictionary is disabled`() {
        mockkObject(DictionaryPreferences)
        every { DictionaryPreferences.isDictionaryEnabled(any()) } returns false
        mockkObject(DictionaryRepository)
        mockkObject(SnippetPreferences)
        every { SnippetPreferences.isSnippetsEnabled(any()) } returns true
        every { SnippetPreferences.loadSnippets(any()) } returns listOf(Snippet(1, "my linkedin", "url"))
        val context = mockk<Context>(relaxed = true)

        assertEquals(
            "send url",
            DictionaryTextPostProcessor.process(context, "send my linkedin")
        )
        verify(exactly = 0) { DictionaryRepository.getCompiledRules(any()) }
    }

    @Test
    fun `seam passes transcript through unchanged when snippets disabled`() {
        mockkObject(DictionaryPreferences)
        every { DictionaryPreferences.isDictionaryEnabled(any()) } returns false
        mockkObject(SnippetPreferences)
        every { SnippetPreferences.isSnippetsEnabled(any()) } returns false
        val context = mockk<Context>(relaxed = true)
        val text = "ordinary dictation text"
        assertSame(text, DictionaryTextPostProcessor.process(context, text))
    }

    // ── Storage: in-memory SharedPreferences fake ───────────────────────────

    @Test
    fun `snapshots serialize and deserialize round trip`() {
        val json = SnippetPreferences.serialize(
            listOf(
                Snippet(1, "my linkedin", "https://linkedin.com/in/u"),
                Snippet(2, "my github", "https://github.com/u")
            )
        )
        val loaded = SnippetPreferences.deserialize(json)
        assertEquals(listOf(1L, 2L), loaded.map { it.id })
        assertEquals("my linkedin", loaded[0].trigger)
        assertEquals("https://github.com/u", loaded[1].expansion)
    }

    @Test
    fun `corrupt json body yields an empty list`() {
        assertEquals(emptyList<Snippet>(), SnippetPreferences.deserialize("not json {{{"))
    }

    @Test
    fun `unknown schema version yields an empty list`() {
        assertEquals(
            emptyList<Snippet>(),
            SnippetPreferences.deserialize("{\"v\":2,\"snippets\":[]}")
        )
    }

    @Test
    fun `malformed entry is skipped without discarding the collection`() {
        val json = "{\"v\":1,\"snippets\":[{\"id\":1,\"t\":\"a\",\"e\":\"b\"},5]}"
        val loaded = SnippetPreferences.deserialize(json)
        assertEquals(listOf(Snippet(1, "a", "b")), loaded)
    }

    @Test
    fun `saveSnippet inserts updates and dedupes case insensitively`() {
        val context = contextWith(FakeSharedPreferences())
        assertEquals(SnippetPreferences.SaveResult.INSERTED, SnippetPreferences.saveSnippet(context, "my linkedin", "url"))
        assertEquals(SnippetPreferences.SaveResult.INSERTED, SnippetPreferences.saveSnippet(context, "my github", "gh"))
        assertEquals(SnippetPreferences.SaveResult.PRESERVED, SnippetPreferences.saveSnippet(context, "MY LINKEDIN", "dup"))
        assertEquals(SnippetPreferences.SaveResult.UPDATED, SnippetPreferences.saveSnippet(context, "my GitHub", "hub", id = 2))
        val snippets = SnippetPreferences.loadSnippets(context)
        assertEquals(2, snippets.size)
        assertEquals(listOf("my linkedin", "my GitHub"), snippets.map { it.trigger })
        assertEquals("hub", snippets.first { it.trigger == "my GitHub" }.expansion)
        assertEquals(3L, snippets.first { it.trigger == "my GitHub" }.id)
    }

    @Test
    fun `saveSnippet rejects invalid input`() {
        val context = contextWith(FakeSharedPreferences())
        assertEquals(SnippetPreferences.SaveResult.PRESERVED, SnippetPreferences.saveSnippet(context, " ", "url"))
        assertEquals(SnippetPreferences.SaveResult.PRESERVED, SnippetPreferences.saveSnippet(context, "my linkedin", "  "))
        assertEquals(
            SnippetPreferences.SaveResult.PRESERVED,
            SnippetPreferences.saveSnippet(context, "x".repeat(101), "url")
        )
        assertEquals(
            SnippetPreferences.SaveResult.PRESERVED,
            SnippetPreferences.saveSnippet(context, "my linkedin", "x".repeat(501))
        )
        assertEquals(emptyList<Snippet>(), SnippetPreferences.loadSnippets(context))
    }

    @Test
    fun `deleteSnippet removes only the target`() {
        val context = contextWith(FakeSharedPreferences())
        SnippetPreferences.saveSnippet(context, "a", "1")
        SnippetPreferences.saveSnippet(context, "b", "2")
        SnippetPreferences.deleteSnippet(context, 1)
        val snippets = SnippetPreferences.loadSnippets(context)
        assertEquals(listOf("b"), snippets.map { it.trigger })
    }

    @Test
    fun `enabled flag defaults to off`() {
        val context = contextWith(FakeSharedPreferences())
        assertEquals(false, SnippetPreferences.isSnippetsEnabled(context))
        SnippetPreferences.setSnippetsEnabled(context, true)
        assertEquals(true, SnippetPreferences.isSnippetsEnabled(context))
    }

    @Test
    fun `missing stored document loads as empty`() {
        val context = contextWith(FakeSharedPreferences())
        assertEquals(emptyList<Snippet>(), SnippetPreferences.loadSnippets(context))
    }

    // ── Test helpers ────────────────────────────────────────────────────────

    private fun contextWith(prefs: SharedPreferences): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return context
    }

    /** Minimal in-memory SharedPreferences for JVM unit tests. */
    private class FakeSharedPreferences : SharedPreferences {
        private val store = HashMap<String, Any>()

        override fun getString(key: String?, defValue: String?): String? = store[key] as? String ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = store[key] as? Boolean ?: defValue
        override fun getInt(key: String?, defValue: Int): Int = store[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = store[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = store[key] as? Float ?: defValue
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            store[key] as? Set<String> ?: defValues
        override fun contains(key: String?): Boolean = key != null && store.containsKey(key)
        override fun getAll(): MutableMap<String, *> = HashMap(store)
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private var clearPending = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                pending[key!!] = value; return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                pending[key!!] = value; return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                pending[key!!] = value; return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                pending[key!!] = value; return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                pending[key!!] = value; return this
            }
            override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
                pending[key!!] = values; return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                pending[key!!] = null; return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearPending = true; return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearPending) store.clear()
                pending.forEach { (key, value) ->
                    if (value == null) store.remove(key) else store[key] = value
                }
                pending.clear()
                clearPending = false
            }
        }
    }
}