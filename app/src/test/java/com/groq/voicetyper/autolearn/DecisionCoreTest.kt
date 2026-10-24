package com.groq.voicetyper.autolearn

import com.groq.voicetyper.autolearn.domain.CorrectionCandidate
import com.groq.voicetyper.autolearn.domain.DecisionCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Android mirror of the Windows Tauri decision-core test suite
 * (`decision_core.rs:326-696`): every fixture is a 1:1 transliteration so
 * a regression on either platform is caught on both.
 */
class DecisionCoreTest {

    private fun knownFrom(vararg pairs: Pair<String, String>): Set<String> =
        pairs.map { DecisionCore.canonicalPairKey(it.first, it.second) }.toSet()

    private val noKnown: Set<String> = emptySet()

    private fun extract(
        original: String,
        edited: String,
        known: Set<String> = noKnown
    ): List<CorrectionCandidate> =
        DecisionCore.extractCorrections(original, edited, known)

    private fun candidate(spoken: String, corrected: String) =
        CorrectionCandidate(spokenText = spoken, correctedText = corrected)

    // -- tokenize ----------------------------------------------------------

    @Test
    fun tokenizeBasic() {
        assertEquals(listOf("hello", "world", "test"), DecisionCore.tokenize("hello world test"))
    }

    @Test
    fun tokenizeEmptyAndWhitespaceOnly() {
        assertTrue(DecisionCore.tokenize("").isEmpty())
        assertTrue(DecisionCore.tokenize("   \t  ").isEmpty())
    }

    // -- editDistance ------------------------------------------------------

    @Test
    fun editDistanceBasic() {
        assertEquals(2, DecisionCore.editDistance("cool", "good"))
        assertEquals(1, DecisionCore.editDistance("abc", "axc"))
        assertEquals(0, DecisionCore.editDistance("abcdef", "abcdef"))
        assertEquals(2, DecisionCore.editDistance("", "ab"))
    }

    // -- findSubstitutions (insertion-prefer ties) -------------------------

    @Test
    fun findSubstitutionsIdentical() {
        val orig = listOf("hello", "world")
        assertTrue(DecisionCore.findSubstitutions(orig, orig).isEmpty())
    }

    @Test
    fun findSubstitutionsSingle() {
        val orig = listOf("hello", "shunade")
        val edited = listOf("hello", "Sinead")
        val subs = DecisionCore.findSubstitutions(orig, edited)
        assertEquals(listOf(Pair("shunade", "Sinead")), subs)
    }

    @Test
    fun findSubstitutionsInsertionOnlyNoPairs() {
        val orig = listOf("hello")
        val edited = listOf("hello", "world")
        assertTrue(DecisionCore.findSubstitutions(orig, edited).isEmpty())
    }

    @Test
    fun findSubstitutionsDeletionOnlyNoPairs() {
        val orig = listOf("hello", "world")
        val edited = listOf("hello")
        assertTrue(DecisionCore.findSubstitutions(orig, edited).isEmpty())
    }

    @Test
    fun findSubstitutionsRc1ShapeYieldsOneBoundaryPair() {
        // RC-1 (plan §1): the OLD Fluence tie rule manufactured
        // `cool->stuff`, `was->agent`, `it->Good`. The faithful traceback
        // produces exactly ONE adjacency pair: [cool, Good].
        val orig = listOf("I", "went", "to", "the", "shunade", "and", "it", "was", "cool")
        val edited = listOf("I", "went", "to", "the", "shunade", "Good", "agent", "stuff")
        val subs = DecisionCore.findSubstitutions(orig, edited)
        assertEquals(1, subs.size)
        assertEquals(Pair("cool", "Good"), subs[0])
    }

    // -- invalidPairReason / isValidPair (the gate table; floor 0.35) ------

    @Test
    fun validPairsPassGates() {
        assertTrue(DecisionCore.isValidPair("shunade", "Sinead", noKnown)) // 0.57
        assertTrue(DecisionCore.isValidPair("Johnatan", "Jonathan", noKnown)) // 0.25
        assertTrue(DecisionCore.isValidPair("recognision", "recognition", noKnown))
        assertTrue(DecisionCore.isValidPair("definately", "definitely", noKnown)) // 0.9
        assertTrue(DecisionCore.isValidPair("accomodate", "accommodate", noKnown))
        assertTrue(DecisionCore.isValidPair("abc", "axc", noKnown)) // 0.333
        assertTrue(DecisionCore.isValidPair("'hello'", "hello", noKnown)) // 0.286
        assertTrue(DecisionCore.isValidPair("forteen", "fourteen", noKnown))
        assertTrue(DecisionCore.isValidPair("ab", "abcd", noKnown)) // 0.5
    }

    @Test
    fun dissimilarPairsRejected() {
        // Screenshot garbage (plan §5): all below the 0.35 floor.
        assertFalse(DecisionCore.isValidPair("stuck", "agent", noKnown))
        assertFalse(DecisionCore.isValidPair("CS50", "tabs", noKnown))
        assertFalse(DecisionCore.isValidPair("have", "Type", noKnown))
        assertFalse(DecisionCore.isValidPair("release", "agent", noKnown))
        assertFalse(DecisionCore.isValidPair("in", "add", noKnown))
        assertFalse(DecisionCore.isValidPair("do", "add", noKnown)) // 0.333, tightest
        assertFalse(DecisionCore.isValidPair("check", "add", noKnown))
        assertFalse(DecisionCore.isValidPair("testing", "ChatGPT", noKnown)) // dist 6/7
        assertEquals(6, DecisionCore.editDistance("testing", "chatgpt")) // 6/7 ≈ 0.857 > 0.65
        assertFalse(DecisionCore.isValidPair("in", "to", noKnown)) // length rule
        assertFalse(DecisionCore.isValidPair("Let", "et", noKnown)) // length rule ONLY
    }

    @Test
    fun caseOnlyPairsSkipped() {
        // OQ-5 (owner-confirmed): reference parity - lowercase-identical
        // pairs are skipped, hit the human Add path in the Words tab.
        assertFalse(DecisionCore.isValidPair("hello", "Hello", noKnown))
        assertFalse(DecisionCore.isValidPair("HELLO", "hello", noKnown))
        assertFalse(DecisionCore.isValidPair("hello", "hello", noKnown))
    }

    @Test
    fun caseInsensitiveGateRejectsCasingPairs() {
        // Live-path regression fixture (Android original gate): groq->Groq
        // surfaced before the case-insensitive port; the shared gates must
        // reject casing pairs via the case-only reason.
        assertFalse(DecisionCore.isValidPair("groq", "Groq", noKnown))
        assertEquals("case-only difference", DecisionCore.invalidPairReason("groq", "Groq", noKnown))
    }

    @Test
    fun shortCorrectedRejected() {
        // OQ-2 (owner-confirmed): corrected words < 3 chars do not surface.
        // Let->et survives the ratio (1/3 = 0.333) - the length rule is the
        // sole killer (plan §5, load-bearing).
        assertFalse(DecisionCore.isValidPair("abcd", "ab", noKnown))
        assertFalse(DecisionCore.isValidPair("ab", "cd", noKnown)) // sim 0 + len
        assertFalse(DecisionCore.isValidPair("a", "b", noKnown))
    }

    @Test
    fun triviallyShortOriginalButUsableCorrectedAllowed() {
        // Only the CORRECTED side has a minimum; ab -> abcd passes (0.5).
        assertTrue(DecisionCore.isValidPair("ab", "abcd", noKnown))
    }

    @Test
    fun numericPairsRejectedByGates() {
        // OQ-6 (owner-confirmed): exact parity - NO numeric-mix rule is
        // ported; the gates reject these on their own.
        assertFalse(DecisionCore.isValidPair("123", "456", noKnown)) // sim 0.0
        assertFalse(DecisionCore.isValidPair("123", "abc", noKnown)) // ratio 1.0
        assertFalse(DecisionCore.isValidPair("abc", "123", noKnown))
    }

    @Test
    fun dissimilarWordPairsRejected() {
        assertFalse(DecisionCore.isValidPair("abc", "xyz", noKnown))
        assertFalse(DecisionCore.isValidPair("banana", "elephant", noKnown))
        assertFalse(DecisionCore.isValidPair("computer", "elephant", noKnown))
    }

    @Test
    fun reportedCaseShwandeNoLongerSurfaces() {
        // OQ-1 (owner-accepted): shwande->Sinead (sim 0.286) sits below the
        // floor; no floor value admits it without re-admitting do->add.
        assertFalse(DecisionCore.isValidPair("shwande", "Sinead", noKnown))
    }

    @Test
    fun residualBoundaryPairsStillPass() {
        // Documented residuals (plan §5): owner-accepted class; one Dismiss
        // silences each forever.
        assertTrue(DecisionCore.isValidPair("cool", "Good", noKnown)) // 0.50 (RC-1)
        assertTrue(DecisionCore.isValidPair("again", "agent", noKnown)) // 0.400
        assertTrue(DecisionCore.isValidPair("shunade", "Sinead", noKnown)) // 0.43 expected case
    }

    @Test
    fun knownPairSkip() {
        val known = knownFrom(Pair("shunade", "Sinead"))
        assertFalse(DecisionCore.isValidPair("shunade", "Sinead", known))
        assertFalse(DecisionCore.isValidPair("SHUNADE", "sinead", known)) // canonical
        assertTrue(DecisionCore.isValidPair("shunade", "Shanade", known)) // different pair
    }

    @Test
    fun emptyStringsRejected() {
        assertFalse(DecisionCore.isValidPair("", "hello", noKnown))
        assertFalse(DecisionCore.isValidPair("hello", "", noKnown))
        assertFalse(DecisionCore.isValidPair("", "", noKnown))
    }

    // -- extractCorrections (JS L137-177 shape) ----------------------------

    @Test
    fun extractSimpleCorrection() {
        val pairs = extract("I went to the shunade", "I went to the Sinead")
        assertEquals(listOf(candidate("shunade", "Sinead")), pairs)
    }

    @Test
    fun extractIdenticalReturnsEmpty() {
        assertTrue(extract("hello world", "hello world").isEmpty())
    }

    @Test
    fun extractEmptyInputs() {
        assertTrue(extract("", "hello world").isEmpty())
        assertTrue(extract("hello world", "").isEmpty())
        assertTrue(extract("", "").isEmpty())
    }

    @Test
    fun extractFullRewriteThroughGuard() {
        // RC-5 leak fixture: `release->replace` (sim 0.571) would pass the
        // per-pair gates; the rewrite guards veto the whole rewrite.
        assertTrue(extract("we should release", "replace the plan").isEmpty())
    }

    @Test
    fun extractQuickBrownFoxRewriteRejected() {
        assertTrue(extract("the quick brown fox", "a slow red cat jumped").isEmpty())
    }

    @Test
    fun extractPartialCorrectionSurvivesVetoes() {
        // 1 sub of 4 words (under the veto) and LCS coverage passes:
        // one legitimate pair survives the vetoes.
        // great->grand: dist 3/5 = 0.6 <= 0.65 -> valid.
        val pairs = extract("this city is great", "this town is grand")
        assertEquals(listOf(candidate("great", "grand")), pairs)
    }

    @Test
    fun extractRc1ShapeSingleResidualCandidate() {
        // RC-1 (plan §1, v4): exactly ONE candidate `cool->Good` survives;
        // the old tie-pairs (`cool->stuff`, `was->agent`, `it->Good`) must
        // not reappear. Boundary-word residual, owner-accepted class.
        val pairs = extract(
            "I went to the shunade and it was cool",
            "I went to the shunade Good agent stuff"
        )
        assertEquals(listOf(candidate("cool", "Good")), pairs)
    }

    @Test
    fun extractKnownPairsAloneFilter() {
        val known = knownFrom(Pair("shunade", "Sinead"))
        assertTrue(extract("I went to the shunade", "I went to the Sinead", known).isEmpty())
    }

    @Test
    fun extractQuotedTokensFormNoPair() {
        // Round-4 F4 (pipeline truth): the tokenizer strips edge quotes on
        // BOTH sides, so the tokens are identical and no substitution
        // window forms. In production no candidate ever reaches the judge.
        assertTrue(extract("hello", "'hello'").isEmpty())
        assertTrue(extract("'hello'", "hello").isEmpty())
    }

    @Test
    fun extractDuplicatePairDeduped() {
        // Pair-level dedup within one call (D4): two INDEPENDENT
        // shunade->Sinead regions, separated by shared words so each gets
        // its own substitution window. Both survive the gates, so the
        // second is dropped solely by the seenPairs guard.
        val pairs = extract("the shunade is fine shunade", "the Sinead is fine Sinead")
        assertEquals(1, pairs.size)
        assertEquals(candidate("shunade", "Sinead"), pairs[0])
    }

    @Test
    fun invalidPairReasonCoversSkipChain() {
        // Round-4 F2: the reason mirror must walk the gates in the SAME
        // order as isValidPair, with null exactly for accepted pairs.
        val known = knownFrom(Pair("shunade", "Sinead"))
        assertEquals("already in dictionary", DecisionCore.invalidPairReason("shunade", "Sinead", known))
        assertEquals("case-only difference", DecisionCore.invalidPairReason("HELLO", "hello", noKnown))
        // corrected < 3 chars ("dr" is 2); all earlier gates miss.
        assertEquals("corrected shorter than 3 chars", DecisionCore.invalidPairReason("shunade", "dr", noKnown))
        assertEquals("below similarity floor", DecisionCore.invalidPairReason("stuck", "agent", noKnown))
        // 4/7 ≈ 0.571 <= 0.65, len ok, not case-equivalent, unknown -> valid.
        assertEquals(null, DecisionCore.invalidPairReason("shunade", "Sinead", noKnown))
        // Gate ORDER: do/add is known AND dissimilar (dist 2/3 > 0.65), so
        // reporting "already in dictionary" proves the known gate wins.
        assertEquals(
            "already in dictionary",
            DecisionCore.invalidPairReason("do", "add", knownFrom(Pair("do", "add")))
        )
    }

    @Test
    fun extractMultisentencePunctuationOnlyUnchanged() {
        // Tokenizer strips edge punctuation: `hello world.` tokenizes to
        // `hello world` - no candidates.
        assertTrue(extract("hello world", "hello world.").isEmpty())
        // Emoji-only token vanishes at tokenization (no pairing possible).
        assertTrue(extract("hello world", "hello 🌍").isEmpty())
    }

    @Test
    fun extractRemainingWordCaseOnlySkipped() {
        // Case-only edit inside the anchored span: lowercase-identical
        // gates reject the pair -> nothing to learn (OQ-5).
        assertTrue(extract("hello world", "Hello World").isEmpty())
    }

    // -- canonicalPairKey --------------------------------------------------

    @Test
    fun canonicalKeyTrimsAndCaseFolds() {
        assertEquals(
            DecisionCore.canonicalPairKey("Shunade", "Sinead"),
            DecisionCore.canonicalPairKey("shunade", "SineAd")
        )
        assertFalse(
            DecisionCore.canonicalPairKey("shunade", "Sinead") ==
                DecisionCore.canonicalPairKey("shunade", "Shanade")
        )
    }
}
