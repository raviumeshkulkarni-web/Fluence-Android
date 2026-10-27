package com.groq.voicetyper.autolearn

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.groq.voicetyper.autolearn.domain.AutoLearnFieldSnapshot
import com.groq.voicetyper.autolearn.domain.AutoLearnSessionManager
import com.groq.voicetyper.autolearn.domain.AutoLearnSurface
import io.mockk.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)

class AutoLearnSessionManagerTest {

    private val context = mockk<Context>(relaxed = true)
    private val editorInfo = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
    }

    // Node-derived reality: accessibility snapshots can never carry the
    // IMF-contract members (no personalized learning flag / trustworthy
    // inputType), so they stay null (unknown) - see
    // AutoLearnFieldSnapshot.isObservationAllowedForBubble for the gate
    // policy that tolerates these two while denying every read signal.
    private val bubbleSnapshot = AutoLearnFieldSnapshot(
        packageName = "com.example.app",
        isPassword = false,
        isNullInputType = null,
        noPersonalizedLearning = null
    )

    @Before
    fun setUp() {
        mockkObject(AutoLearnPreferences)
        every { AutoLearnPreferences.isAutoLearnEnabled(any()) } returns true
        AutoLearnSessionManager.onStartInput(editorInfo, context)
    }

    @After
    fun tearDown() {
        AutoLearnSessionManager.endSession()
        AutoLearnSessionManager.dispatcherOverride = null
        AutoLearnSessionManager.settleDelayOverride = null
        AutoLearnSessionManager.timeoutDelayOverride = null
        unmockkAll()
    }

    private fun stubLivePath(
        dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
        settleDelay: Long = 0L
    ) {
        mockkObject(com.groq.voicetyper.PrivacyPreferences)
        every {
            com.groq.voicetyper.PrivacyPreferences.isPackageExcluded(any(), any())
        } returns false
        mockkObject(SuggestionRepository)
        coEvery { SuggestionRepository.knownPairKeys(any()) } returns emptySet()
        coJustRun { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
        AutoLearnSessionManager.dispatcherOverride = dispatcher
        AutoLearnSessionManager.settleDelayOverride = settleDelay
    }

    private fun bubbleArm(
        snapshot: AutoLearnFieldSnapshot = bubbleSnapshot,
        injected: String = "I went to the shunade",
        confirmed: String = "I went to the shunade"
    ): Boolean = AutoLearnSessionManager.armBubbleSession(
        field = snapshot,
        windowId = 42L,
        injectedText = injected,
        confirmedFieldText = confirmed,
        context = context
    )

    @Test
    fun oversizedCommittedTextStopsObservationWithoutRecordingCandidates() {
        mockkObject(SuggestionRepository)
        AutoLearnSessionManager.startSession("x".repeat(10_001), context)

        AutoLearnSessionManager.onTextUpdated("short edit", context)

        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
    }

    @Test
    fun oversizedEditedTextStopsObservationWithoutRecordingCandidates() {
        mockkObject(SuggestionRepository)
        AutoLearnSessionManager.startSession("short committed text", context)

        AutoLearnSessionManager.onTextUpdated("y".repeat(10_001), context)

        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
    }

    @Test
    fun excludedPackage_doesNotStartObservation() {
        val excludedInfo = EditorInfo().apply {
            packageName = "com.example.excluded"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }
        mockkObject(com.groq.voicetyper.PrivacyPreferences)
        every {
            com.groq.voicetyper.PrivacyPreferences.isPackageExcluded(context, "com.example.excluded")
        } returns true
        mockkObject(SuggestionRepository)

        AutoLearnSessionManager.onStartInput(excludedInfo, context)
        AutoLearnSessionManager.startSession("sensitive text", context)
        AutoLearnSessionManager.onTextUpdated("sensitive edit", context)

        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
    }

    @Test
    fun duplicateCandidatesWithinOneSessionRecordedOnce() {
        // Windows monitor session-dedup parity (monitor.rs): cursor moves
        // within one session must not re-count the same canonical pair.
        stubLivePath()
        AutoLearnSessionManager.startSession("I went to the shunade", context)

        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)
        AutoLearnSessionManager.onTextUpdated("I went to the SINEAD", context) // case variant folds canonically

        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "SINEAD", any())
        }
    }

    @Test
    fun newSessionClearsPairKeysAndRecordsAgain() {
        stubLivePath()
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)
        AutoLearnSessionManager.endSession()

        // Real IME flow: the next field focus calls onStartInput, which
        // re-establishes the privacy gate before a new startSession.
        AutoLearnSessionManager.onStartInput(editorInfo, context)
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)

        coVerify(exactly = 2) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
    }

    @Test
    fun candidatesNotPassingGatesNeverReachRepository() {
        stubLivePath()
        AutoLearnSessionManager.startSession("stuck agent release", context)
        AutoLearnSessionManager.onTextUpdated("add tabs agent stuck release plan", context)

        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
    }

    @Test
    fun observedUnsettledChangeFlushesAtRegularClose() {
        // Windows final diff (monitor.rs:515-548): a regular close while the
        // last observed state never settled still diffs it once, and the
        // flush cannot pollute the next session's dedup set (its key copy
        // is flush-local).
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher, settleDelay = 500L)

        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context) // settle armed, timer not reached
        AutoLearnSessionManager.endSession()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }

        // The next session forms its own valid observation -> recorded again.
        AutoLearnSessionManager.onStartInput(editorInfo, context)
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 2) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
    }

    @Test
    fun privacyFlippedObservationAbortsWritesWithoutFinalDiff() {
        // Review round 2 (must-fix 1), round-3 form: a PRIVACY revocation
        // mid-observation must abort the recorded candidate and must NOT
        // rescue it through the exit flush (Windows SecureFieldDetected
        // parity - the observed content is never persisted).
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher)
        val excludedInfo = EditorInfo().apply {
            packageName = "com.example.excluded"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }
        // One-shot poison (review round 4: a persistent stub leaked into the
        // second phase and killed the fresh session there instead). Only the
        // first lookup of phase 1 revokes privacy.
        var poisoned = false
        coEvery { SuggestionRepository.knownPairKeys(any()) } coAnswers {
            if (!poisoned) {
                poisoned = true
                every {
                    com.groq.voicetyper.PrivacyPreferences.isPackageExcluded(any(), "com.example.excluded")
                } returns true
                AutoLearnSessionManager.onStartInput(excludedInfo, context)
            }
            emptySet<String>()
        }

        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any())
        }

        // A fresh privacy-allowed session still forms its own observation -> once.
        every { com.groq.voicetyper.PrivacyPreferences.isPackageExcluded(any(), any()) } returns false
        AutoLearnSessionManager.onStartInput(editorInfo, context)
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
    }

    @Test
    fun recordFailureDoesNotCrashAndReleasesPairKeyForRetry() {
        // Review round 2 (must-fix 2): DAO failures (disk full, corrupt DB)
        // fail open instead of crashing the IME, and the dedup key is
        // released so a later legitimate observation can retry.
        stubLivePath()
        coEvery {
            SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any())
        } throws RuntimeException("disk full")
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)

        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }

        // Retry works because the failed write released the pair key. The
        // second observation must differ from the already-classified
        // baseline, otherwise the settle gate treats it as a no-op
        // (Windows identical-read rule).
        coJustRun { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead now", context)

        coVerify(exactly = 2) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
    }

    @Test
    fun keystrokeBurstCollapsesIntoSingleSettledDiff() {
        // Windows SETTLED_POLLS parity (monitor.rs:77-82): intermediate
        // typing states must not become candidates - the burst collapses
        // into one diff of the final text.
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher, settleDelay = 500L)
        AutoLearnSessionManager.startSession("I definately love this", context)

        AutoLearnSessionManager.onTextUpdated("I definely love this", context)
        AutoLearnSessionManager.onTextUpdated("I definitel love this", context)
        AutoLearnSessionManager.onTextUpdated("I definitely love this", context)

        testDispatcher.scheduler.advanceTimeBy(499)
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any())
        }

        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "definately", "definitely", any())
        }
        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), "definately", "definely", any())
        }
        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), "definately", "definitel", any())
        }
    }

    @Test
    fun midTypingPrivacyRevocationSuppressesFinalDiff() {
        // Windows SecureFieldDetected/ReadOnlyField parity: a
        // privacy-revoking close stops immediately; observed content is
        // dropped, NOT flushed by the exit diff.
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher, settleDelay = 500L)
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)

        every { com.groq.voicetyper.PrivacyPreferences.isPackageExcluded(any(), any()) } returns true
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead again", context)

        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any())
        }
    }

    @Test
    fun blankFieldUpdateCancelsPendingArm() {
        // Windows NoValue parity (monitor.rs:466-478): an empty field is
        // never classified - clearing the field cancels the armed
        // observation so the close cannot flush it as a final diff
        // (monitor.rs:523-528 trusts only a readable last state).
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher, settleDelay = 500L)
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)
        AutoLearnSessionManager.onTextUpdated("", context)
        AutoLearnSessionManager.endSession()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any())
        }
    }

    @Test
    fun staleObservationAfterDeferredLookupAbortsRecording() {
        // Review round 4: the dictionary lookup and extraction are not
        // interruption points, so a newer keystroke (which cancels this arm
        // and bumps the settle epoch) must abort the stale observation at
        // its next guard - the intermediate never reaches the repository.
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher)
        var firstLookup = true
        coEvery { SuggestionRepository.knownPairKeys(any()) } coAnswers {
            if (firstLookup) {
                firstLookup = false
                // Keystroke lands while the lookup is in flight: this arm is
                // cancelled and a newer one armed; the in-flight work continues.
                AutoLearnSessionManager.onTextUpdated("I went to the Sineaddx", context)
                emptySet<String>()
            } else {
                emptySet<String>()
            }
        }
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)
        testDispatcher.scheduler.advanceUntilIdle()

        // The stale observation ("Sinead") aborted; only the newer arm
        // ("Sineaddx", a distinct canonical pair) recorded - once.
        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sineaddx", any())
        }
    }

    @Test
    fun bubbleArmFailsClosedOnDeniedFieldRules() {
        // Positive security evidence always denies arming (review
        // requirement: prefer a missed learning opportunity over ever
        // observing a password field). Rules run BEFORE package lookups.
        mockkObject(SuggestionRepository)
        val password = bubbleSnapshot.copy(isPassword = true)
        val nullType = bubbleSnapshot.copy(isNullInputType = true)
        val flaggedNoLearning = bubbleSnapshot.copy(noPersonalizedLearning = true)
        val passwordUnknown = bubbleSnapshot.copy(isPassword = null)
        // An unknown password attribute denies identically (never assume
        // a field is safe because metadata is missing).
        val everythingUnknown = AutoLearnFieldSnapshot(null, null, null, null)
        assertFalse(bubbleArm(snapshot = password))
        assertFalse(bubbleArm(snapshot = nullType))
        assertFalse(bubbleArm(snapshot = flaggedNoLearning))
        assertFalse(bubbleArm(snapshot = passwordUnknown))
        assertFalse(bubbleArm(snapshot = everythingUnknown))
        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
    }

    @Test
    fun bubbleArmAllowsUnknowableImfMetadata() {
        // Documented Coverage A divergence: accessibility nodes can never
        // report the IMF-contract metadata, so unknown (null) is tolerated
        // at arming while every positive assertion denies (see the deny
        // test above). Protection rests on the password read, the
        // excluded-/target-package checks and per-update revocations.
        stubLivePath()
        assertTrue(bubbleArm())
    }

    @Test
    fun bubbleArmFailsClosedOnMissingConfirmationEvidence() {
        stubLivePath()
        // FieldTooLarge / InjectedTextNotObserved parity
        // (monitor.rs:244-273): the confirmed read must exist, fit the
        // field cap and contain the exact injected text.
        assertFalse(bubbleArm(injected = "   "))
        assertFalse(bubbleArm(confirmed = ""))
        assertFalse(bubbleArm(confirmed = "z".repeat(10_001)))
        assertFalse(bubbleArm(confirmed = "only previous content without dictation"))
        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
    }

    @Test
    fun bubbleArmDeniedOnExcludedPackageThenArmsWhenAllowed() {
        stubLivePath()
        every {
            com.groq.voicetyper.PrivacyPreferences.isPackageExcluded(any(), "com.example.app")
        } returns true
        assertFalse(bubbleArm())
        every {
            com.groq.voicetyper.PrivacyPreferences.isPackageExcluded(any(), "com.example.app")
        } returns false
        assertTrue(bubbleArm())
    }

    @Test
    fun bubbleArmDeniedWhenMasterToggleOff() {
        // Unlike the IME surface, bubble arming re-checks the master
        // toggle itself (no onStartInput ran for it).
        every { AutoLearnPreferences.isAutoLearnEnabled(any()) } returns false
        assertFalse(bubbleArm())
    }

    @Test
    fun bubbleArmSupersedesPendingImeSessionWithoutFlush() {
        // Windows Superseded parity: cross-surface takeover drops the
        // previous session's pending input WITHOUT the exit diff; the new
        // session then observes normally.
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher, settleDelay = 500L)
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context) // IME settle armed
        assertTrue(bubbleArm())

        // runCurrent (not advanceUntilIdle): the only queued tasks are the
        // canceled IME arm and the session timeout - advancing virtual time
        // past the 30s timeout would close the session under test before
        // phase 2 observes (the timer is Windows-parity correct; the test
        // simply must not fast-forward it here).
        testDispatcher.scheduler.runCurrent()
        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }

        // The bubble session itself observes and records.
        AutoLearnSessionManager.onTextUpdated(
            "I went to the Sinead", context, bubbleSnapshot, origin = AutoLearnSurface.BUBBLE
        )
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
    }

    @Test
    fun bubbleUpdatePasswordFieldAbortsObservationAndFlush() {
        // Windows SecureFieldDetected per-read parity: positive downgrade
        // evidence mid-session exits WITHOUT the exit diff; security exits
        // are origin-agnostic and revoke first.
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher, settleDelay = 500L)
        assertTrue(bubbleArm())
        AutoLearnSessionManager.onTextUpdated(
            "I went to the Sinead", context, bubbleSnapshot, origin = AutoLearnSurface.BUBBLE
        )
        AutoLearnSessionManager.onTextUpdated(
            "I went to the Sinead now",
            context,
            bubbleSnapshot.copy(isPassword = true),
            origin = AutoLearnSurface.BUBBLE
        )
        AutoLearnSessionManager.endSession(AutoLearnSurface.BUBBLE)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
    }

    @Test
    fun bubbleUpdateForeignPackageAbortsObservation() {
        // Same-session identity drift (review finding: target identity
        // must be validated per update): an observed read from a package
        // other than the armed target is evidence of a field switch the
        // close hook missed -> security exit, no flush.
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher, settleDelay = 500L)
        assertTrue(bubbleArm())
        AutoLearnSessionManager.onTextUpdated(
            "I went to the Sinead", context, bubbleSnapshot, origin = AutoLearnSurface.BUBBLE
        )
        AutoLearnSessionManager.onTextUpdated(
            "I went to the Sinead again",
            context,
            bubbleSnapshot.copy(packageName = "com.example.other"),
            origin = AutoLearnSurface.BUBBLE
        )
        AutoLearnSessionManager.endSession(AutoLearnSurface.BUBBLE)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
    }

    @Test
    fun imeTeardownMustNotFlushBubbleSessionButBubbleCloseDoes() {
        // Review finding: AOSP `doFinishInput()` finishes views BEFORE the
        // next target starts, so the IME cannot know the next field's
        // security posture - a regular IME-origin close must not exit-flush
        // a bubble-armed session. Review fix 12 goes further: the foreign
        // close does not even tear the session down. The bubble's own
        // authoritative close still flushes (FocusChanged/Timeout parity).
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher, settleDelay = 500L)
        assertTrue(bubbleArm())
        AutoLearnSessionManager.onTextUpdated(
            "I went to the Sinead", context, bubbleSnapshot, origin = AutoLearnSurface.BUBBLE
        )
        AutoLearnSessionManager.endSession() // IME-origin close: no flush, no teardown (review fix 12)

        // The session stays alive under the skipped foreign close, so
        // supersede it BEFORE virtual time runs - the new session's begin
        // drops the old pending diff (Windows Superseded parity).
        assertTrue(bubbleArm(injected = "I definately love this", confirmed = "I definately love this"))
        AutoLearnSessionManager.onTextUpdated(
            "I definitely love this", context, bubbleSnapshot, origin = AutoLearnSurface.BUBBLE
        )
        AutoLearnSessionManager.endSession(AutoLearnSurface.BUBBLE)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "definately", "definitely", any())
        }
    }

    @Test
    fun bubbleSessionTimeoutFlushesFinalDiffOnlyForActiveSession() {
        // Windows timeout parity (monitor.rs:371-376): the timeout is the
        // session's own regular close (final diff runs), and only the
        // ACTIVE session's timer can fire - an early OWN close disposes the
        // scope and with it the timer, while a foreign close is skipped
        // entirely (review fix 12).
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher, settleDelay = 500L)
        AutoLearnSessionManager.timeoutDelayOverride = 100L
        assertTrue(bubbleArm())
        AutoLearnSessionManager.onTextUpdated(
            "I went to the Sinead", context, bubbleSnapshot, origin = AutoLearnSurface.BUBBLE
        )
        AutoLearnSessionManager.endSession() // foreign IME-origin close: skipped (no flush, no teardown)

        // The supersede drops the still-live session's pending diff BEFORE
        // virtual time runs; the fresh session then runs out its own timer
        // and classifies the un-settled edit once (timeout = own close).
        assertTrue(bubbleArm(injected = "I definately love this", confirmed = "I definately love this"))
        AutoLearnSessionManager.onTextUpdated(
            "I definitely love this", context, bubbleSnapshot, origin = AutoLearnSurface.BUBBLE
        )
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "definately", "definitely", any())
        }
    }

    @Test
    fun bubbleAnchoredObservationIgnoresEditsOutsideTheSpan() {
        // Windows span-anchor parity (correction_extractor.rs): a bubble
        // session observes the FULL field; an edit in a sentence AFTER the
        // injection breaks the anchor and must never flush cross-sentence
        // garbage pairs (`or -> Message` class) - neither via the settle
        // gate nor via the bubble path's regular close exit diff.
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher, settleDelay = 500L)
        assertTrue(
            bubbleArm(
                injected = "shunade",
                confirmed = "TODO: send invoice to shunade tomorrow. NOTE: buy groceries"
            )
        )
        AutoLearnSessionManager.onTextUpdated(
            "TODO: send invoice to shunade tomorrow. NOTE: buy milk",
            context,
            bubbleSnapshot,
            origin = AutoLearnSurface.BUBBLE
        )
        AutoLearnSessionManager.onTextUpdated(
            "TODO: send invoice to Sinead tomorrow. NOTE: buy milk",
            context,
            bubbleSnapshot,
            origin = AutoLearnSurface.BUBBLE
        )
        AutoLearnSessionManager.endSession(AutoLearnSurface.BUBBLE)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
    }

    @Test
    fun bubbleAnchoredObservationStillRecordsInsideTheSpan() {
        // Anchor intact (head/tail context untouched): the injected span's
        // counterpart is diffed against the injection and the pair records
        // like the IME surface's. Multi-token injection so the rewrite
        // guard accepts the span diff (single-token spans veto - parity
        // pinned in DecisionCoreTest).
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher, settleDelay = 500L)
        assertTrue(
            bubbleArm(
                injected = "the shunade project",
                confirmed = "Report for the shunade project"
            )
        )
        AutoLearnSessionManager.onTextUpdated(
            "Report for the Sinead project",
            context,
            bubbleSnapshot,
            origin = AutoLearnSurface.BUBBLE
        )
        AutoLearnSessionManager.endSession(AutoLearnSurface.BUBBLE)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
    }

    @Test
    fun foreignSurfaceRegularCloseLeavesBubbleSessionForItsOwnFlush() {
        // Review fix 12, positive form: the skipped foreign close neither
        // flushes nor kills the session - the pending diff remains, and the
        // surface's OWN close still classifies it (Windows closes the
        // session whose field it observed, never another one).
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher, settleDelay = 500L)
        assertTrue(bubbleArm())
        AutoLearnSessionManager.onTextUpdated(
            "I went to the Sinead", context, bubbleSnapshot, origin = AutoLearnSurface.BUBBLE
        )
        AutoLearnSessionManager.endSession() // foreign (IME) regular close: skipped
        AutoLearnSessionManager.endSession(AutoLearnSurface.BUBBLE) // its own close
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
    }

    @Test
    fun crossSurfaceObservationFeedDoesNotTouchBubbleSettleState() {
        // Review fix 17: foreign-surface observation churn (an IME
        // selection update while a bubble session runs) neither arms nor
        // cancels nor classifies the other surface's pending region.
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher, settleDelay = 500L)
        assertTrue(bubbleArm())
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context, origin = AutoLearnSurface.IME)
        testDispatcher.scheduler.advanceTimeBy(501)
        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }

        // The bubble-origin feed still flows on the same session.
        AutoLearnSessionManager.onTextUpdated(
            "I went to the Sinead", context, bubbleSnapshot, origin = AutoLearnSurface.BUBBLE
        )
        AutoLearnSessionManager.endSession(AutoLearnSurface.BUBBLE)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
    }

    @Test
    fun hostWindowDriftRevokesBubbleSessionWithoutFlush() {
        // Review fix 19: a same-surface anchor event from another window id
        // is positive evidence the anchored surface moved - security-
        // equivalent revoke, pending diff never flushed.
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher, settleDelay = 500L)
        assertTrue(bubbleArm()) // armed with windowId 42
        AutoLearnSessionManager.onTextUpdated(
            "I went to the Sinead", context, bubbleSnapshot, origin = AutoLearnSurface.BUBBLE
        )
        AutoLearnSessionManager.onTextUpdated(
            "I went to the Sinead again",
            context,
            bubbleSnapshot,
            origin = AutoLearnSurface.BUBBLE,
            windowId = 99L
        )
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
    }
}
