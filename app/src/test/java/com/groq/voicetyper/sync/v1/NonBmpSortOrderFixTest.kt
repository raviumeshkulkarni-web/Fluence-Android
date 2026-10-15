package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit follow-up (reviewer R-3): sorts that decide the ORDER OF BYTES ON THE
 * WIRE must compare strings in UTF-8 byte order, because Windows emits the same
 * envelope from a `BTreeMap`, whose iteration is lexicographic `Ord` on bytes.
 *
 * Kotlin's natural `String` ordering is UTF-16 code-unit order. The two orders
 * disagree when one key holds a supplementary-plane character (surrogate pair,
 * lead unit 0xD800-0xDBFF) and another holds U+E000..U+FFFF:
 *
 *   UTF-8  : U+FFFD = EF BF BD , U+1F600 = F0 9F 98 80  ->  astral is LARGER
 *   UTF-16 : U+FFFD = FFFD (65533), U+1F600 = D83D DE00   ->  U+FFFD  is LARGER
 *
 * That is a frozen-contract violation (two platforms emitting the same logical
 * envelope as different bytes) and, in `Merge`, can make two devices re-PUT each
 * other's bytes forever.
 *
 * Every pre-existing fixture uses ASCII keys, which is why the suite never saw
 * it. These are the missing non-BMP vectors.
 */
class NonBmpSortOrderFixTest {

    private val bmp = "\uFFFD"
    private val astral = "\uD83D\uDE00"

    private fun dict(spoken: String, syncId: String): DictionaryRecord {
        return DictionaryRecord(
            syncId = syncId,
            businessKey = DictionaryRecord.businessKeyOf(spoken),
            spoken = spoken,
            corrected = spoken,
            isEnabled = true,
            updatedAt = 1700000000000L,
            deletedAt = null,
            deviceId = "dev-a"
        )
    }

    @Test
    fun premiseOrderingDiffers() {
        assertTrue("premise broken", bmp.compareTo(astral) > 0)
        assertTrue("premise broken", compareUtf8(bmp, astral) < 0)
    }

    @Test
    fun comparatorOrdersByUtf8() {
        val a = dict(bmp, "00000000-0000-4000-8000-00000000000a")
        val b = dict(astral, "00000000-0000-4000-8000-00000000000b")
        val cmp = utf8Comparator<DictionaryRecord>({ it.businessKey }, { it.syncId })
        assertEquals(a, listOf(a, b).sortedWith(cmp).first())
        assertEquals(a, listOf(b, a).sortedWith(cmp).first())
    }

    @Test
    fun emitOrderIsUtf8() {
        val lo = dict(bmp, "00000000-0000-4000-8000-00000000000a")
        val hi = dict(astral, "00000000-0000-4000-8000-00000000000b")
        val domain = DictionaryDomain(v = 1, entries = listOf(hi, lo))
        val emitted = DomainSerializer.serializeDictionary(domain)

        val astralPos = emitted.indexOf(astral)
        val bmpPos = emitted.indexOf(bmp)
        assertTrue("astral key missing", astralPos >= 0)
        assertTrue("bmp key missing", bmpPos >= 0)
        assertTrue(
            "UTF-8 (EF BF BD < F0 9F 98 80) emits the BMP record first; UTF-16 would emit astral first",
            bmpPos < astralPos
        )
    }

    @Test
    fun emitIsIndependentOfInputOrder() {
        val lo = dict(bmp, "00000000-0000-4000-8000-00000000000a")
        val hi = dict(astral, "00000000-0000-4000-8000-00000000000b")
        val one = DomainSerializer.serializeDictionary(DictionaryDomain(v = 1, entries = listOf(hi, lo)))
        val two = DomainSerializer.serializeDictionary(DictionaryDomain(v = 1, entries = listOf(lo, hi)))
        assertEquals(one, two)
    }
}