package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Test

class NfcBusinessKeyTest {

    @Test
    fun cafe_precomposed_vs_decomposed_same_businessKey() {
        val precomposed = "café" // U+00E9
        val decomposed = "cafe\u0301" // e + combining acute U+0301
        assertEquals(
            DictionaryRecord.businessKeyOf(precomposed),
            DictionaryRecord.businessKeyOf(decomposed)
        )
        assertEquals(
            SnippetRecord.businessKeyOf(precomposed),
            SnippetRecord.businessKeyOf(decomposed)
        )
    }

    @Test
    fun nfc_variants_collapse_to_one_winner() {
        val precomposed = "café"
        val decomposed = "cafe\u0301"
        val a = DictionaryRecord("00000000-0000-4000-8000-0000000000a1", DictionaryRecord.businessKeyOf(precomposed), precomposed, "x", true, 100, null, "d")
        val b = DictionaryRecord("00000000-0000-4000-8000-0000000000a2", DictionaryRecord.businessKeyOf(decomposed), decomposed, "y", true, 200, null, "d")
        val merged = Merge.mergeDictionaries(listOf(a), listOf(b))
        assertEquals(1, merged.size)

        val s1 = SnippetRecord("00000000-0000-4000-8000-0000000000b1", SnippetRecord.businessKeyOf(precomposed), precomposed, "exp", true, 100, null, "d")
        val s2 = SnippetRecord("00000000-0000-4000-8000-0000000000b2", SnippetRecord.businessKeyOf(decomposed), decomposed, "exp2", true, 200, null, "d")
        val m2 = Merge.mergeSnippets(listOf(s1), listOf(s2))
        assertEquals(1, m2.size)
    }
}
