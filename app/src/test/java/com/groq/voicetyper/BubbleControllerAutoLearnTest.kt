package com.groq.voicetyper

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import com.groq.voicetyper.autolearn.AutoLearnPreferences
import com.groq.voicetyper.autolearn.domain.AutoLearnFieldSnapshot
import com.groq.voicetyper.autolearn.domain.AutoLearnSessionManager
import com.groq.voicetyper.autolearn.domain.AutoLearnSurface
import com.groq.voicetyper.autolearn.SuggestionRepository
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * Stage 3 of the bubble coverage plan: the BubbleController side of the
 * auto-learn session - secure-field choke point on every write path,
 * arm-after-confirm read-back injection into
 * [AutoLearnSessionManager.armBubbleSession], and lifecycle closes that
 * never reach across surfaces (a bubble hide must leave an IME-origin
 * session alive).
 */
class BubbleControllerAutoLearnTest {

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: CoroutineDispatcher
    private lateinit var context: Context
    private lateinit var node: AccessibilityNodeInfo

    // Backing field for the node read-back; tests flip it to simulate the
    // target app's text state catching up with the injected write.
    private var textState: String = ""

    private val bubbleSnapshot = AutoLearnFieldSnapshot(
        packageName = "com.example.normal",
        isPassword = false,
        isNullInputType = null,
        noPersonalizedLearning = null
    )

    private val imeInfo = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
    }

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context

        // Clipboard machinery: relaxed Context would hand back a generic
        // mock that fails the `as? ClipboardManager` cast, silently diverting
        // every injection into an empty path. Stub it explicitly.
        val clipboard = mockk<ClipboardManager>(relaxed = true)
        every { clipboard.primaryClip } returns mockk<ClipData>(relaxed = true)
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard

        node = mockk(relaxed = true)
        every { node.packageName } returns "com.example.normal"
        every { node.className } returns "android.widget.EditText"
        every { node.isPassword } returns false
        every { node.textSelectionStart } returns -1
        every { node.textSelectionEnd } returns -1
        every { node.isShowingHintText } returns false
        every { node.hintText } returns null
        every { node.actionList } returns null
        every { node.windowId } returns 5
        every { node.refresh() } returns true
        every { node.text } answers { textState }

        // Framework static: AccessibilityNodeInfo.obtain is unimplemented in
        // the stub jar and returns null, which would leave the controller
        // without an active node after showBubble. Return our node instead.
        mockkStatic(AccessibilityNodeInfo::class)
        every { AccessibilityNodeInfo.obtain(any<AccessibilityNodeInfo>()) } returns node

        // Arm read-back needs framework-portal-free manager wiring.
        AutoLearnSessionManager.dispatcherOverride = dispatcher
        AutoLearnSessionManager.settleDelayOverride = 500L
        BubbleController.dispatcherOverride = dispatcher

        mockkObject(PrivacyPreferences)
        every { PrivacyPreferences.isPackageExcluded(any(), any()) } returns false
        // BubbleController always consults the excluded map first - keep it
        // permissive here unless a test re-stubs it.
        mockkObject(TranscriptionSessionManager)
        every { TranscriptionSessionManager.cancelPreWarm() } returns Unit
        every { TranscriptionSessionManager.preWarmOfflinePipeline(any()) } returns Unit
        every { TranscriptionSessionManager.cancelRecording(any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        AutoLearnSessionManager.endSession()
        AutoLearnSessionManager.dispatcherOverride = null
        AutoLearnSessionManager.settleDelayOverride = null
        BubbleController.dispatcherOverride = null
        textState = ""
        unmockkAll()
    }

    /** Live auto-learn wiring: permissive privacy + recording repository. */
    private fun stubLiveLearnPath() {
        mockkObject(AutoLearnPreferences)
        every { AutoLearnPreferences.isAutoLearnEnabled(any()) } returns true
        mockkObject(SuggestionRepository)
        coEvery { SuggestionRepository.knownPairKeys(any()) } returns emptySet()
        coJustRun { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
    }

    /**
     * Plants the node into the controller through the regular show path so
     * the write paths see it as the active target.
     */
    private fun showBubbleFor(node: AccessibilityNodeInfo) {
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
        BubbleController.showBubble(context, node)
    }

    private fun pasteDeliveredPath() {
        // node.performAction(action) == ACTION_PASTE: delivered;
        // node.performAction(action, bundle) == SET_TEXT fallback: unused.
        every { node.performAction(any<Int>()) } returns true
    }

    @Test
    fun deliveredInjectionArmsAfterConfirmedReadbackThenRecordsCorrection() {
        stubLiveLearnPath()
        pasteDeliveredPath()
        textState = "Report for "
        showBubbleFor(node)

        // Multi-token dictation: under the Windows span-anchor the injected
        // span's counterpart is diffed against the injection (single-token
        // spans are cleanly vetoed by the LCS rewrite guard - parity test
        // lives in DecisionCoreTest).
        BubbleController.injectText(context, "the shunade project ")

        // First read-back fires immediately: the field has not caught up yet.
        scheduler.runCurrent()
        textState = "Report for the shunade project "
        // Second attempt at +250 ms sees the confirmed text and arms.
        scheduler.advanceTimeBy(251L)

        // The user then corrects the dictated word; a settled update flows
        // into the bubble-armed session and reaches the repository.
        AutoLearnSessionManager.onTextUpdated(
            "Report for the Sinead project",
            context,
            bubbleSnapshot,
            origin = AutoLearnSurface.BUBBLE
        )
        scheduler.advanceTimeBy(501L)

        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
    }

    @Test
    fun unconfirmedInjectionNeverArmsASession() {
        stubLiveLearnPath()
        pasteDeliveredPath()
        textState = "I went to the "
        showBubbleFor(node)

        BubbleController.injectText(context, "shunade ")
        // The field read never contains the injected text within the
        // read-back budget: no arm, and a later update has no live session.
        scheduler.advanceUntilIdle()
        AutoLearnSessionManager.onTextUpdated(
            "I went to the Sinead",
            context,
            bubbleSnapshot,
            origin = AutoLearnSurface.BUBBLE
        )
        scheduler.advanceUntilIdle()

        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any())
        }
    }

    @Test
    fun pasteFailureFallbackSetTextStillArmsAfterConfirmedReadback() {
        stubLiveLearnPath()
        // ACTION_PASTE fails; the SET_TEXT fallback succeeds.
        every { node.performAction(any<Int>()) } returns false
        every { node.performAction(any<Int>(), any<android.os.Bundle>()) } returns true
        textState = "Report for "
        showBubbleFor(node)

        BubbleController.injectText(context, "the shunade project ")
        scheduler.runCurrent()
        textState = "Report for the shunade project "
        scheduler.advanceTimeBy(251L)

        AutoLearnSessionManager.onTextUpdated(
            "Report for the Sinead project",
            context,
            bubbleSnapshot,
            origin = AutoLearnSurface.BUBBLE
        )
        scheduler.advanceTimeBy(501L)

        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
    }

    @Test
    fun secureWritePathRefusesActionAndRevokesSession() {
        stubLiveLearnPath()
        // The controller believes this node is the active target; a
        // mid-session discovery marks it secure (password class hint).
        every { node.className } returns "com.example.PasswordTextField"
        // A live bubble session exists BEFORE the write path runs (the
        // revoke must kill it without any flush).
        assertTrue(AutoLearnSessionManager.armBubbleSession(
            field = bubbleSnapshot,
            windowId = 9L,
            injectedText = "shunade",
            confirmedFieldText = "I went to the shunade",
            context = context
        ))
        showBubbleFor(node)

        // Non-empty field so the delete path reaches the SET_TEXT action
        // (and therefore the secure choke) instead of no-op'ing on an empty
        // field before any performAction runs.
        textState = "abcde"
        BubbleController.performDeleteChars(3)

        // Nothing was written and the session is gone: later updates stop.
        verify(exactly = 0) { node.performAction(any<Int>()) }
        verify(exactly = 0) { node.performAction(any<Int>(), any<android.os.Bundle>()) }
        AutoLearnSessionManager.onTextUpdated(
            "I went to the Sinead",
            context,
            bubbleSnapshot,
            origin = AutoLearnSurface.BUBBLE
        )
        scheduler.advanceUntilIdle()
        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any())
        }
    }

    @Test
    fun bubbleHideClosesBubbleSessionButLeavesImeSessionAlive() {
        stubLiveLearnPath()
        showBubbleFor(node)

        // A bubble-origin session closes with the bubble (regular close).
        assertTrue(AutoLearnSessionManager.armBubbleSession(
            field = bubbleSnapshot,
            windowId = 9L,
            injectedText = "shunade",
            confirmedFieldText = "I went to the shunade",
            context = context
        ))
        BubbleController.hideBubble()
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context, bubbleSnapshot, origin = AutoLearnSurface.BUBBLE)
        scheduler.advanceUntilIdle()
        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any())
        }

        // An IME-origin session must survive generic bubble hide churn -
        // cross-surface overreach is exactly what the review forbid. The IME
        // surface arms through its own lifecycle: onStartInput first.
        AutoLearnSessionManager.onStartInput(imeInfo, context)
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        BubbleController.hideBubble()
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)
        scheduler.advanceUntilIdle()
        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }

    @Test
    fun secureUpdateMidSessionPreambleStillRevokes_fromControllerFeed() {
        // Combo guard: the manager-side positive-evidence revalidation (from
        // Stage 1) still holds when the update feed arrives through the
        // controller-integrated pipeline on a bubble-origin session.
        stubLiveLearnPath()
        showBubbleFor(node)
        pasteDeliveredPath()
        textState = "I went to the "
        BubbleController.injectText(context, "shunade ")
        scheduler.runCurrent()
        textState = "I went to the shunade "
        scheduler.advanceTimeBy(251L)

        // The field turned out to be a password field between the arm and
        // the next update: revoke-first, no record, no flush.
        AutoLearnSessionManager.onTextUpdated(
            "I went to the Sinead",
            context,
            bubbleSnapshot.copy(isPassword = true),
            origin = AutoLearnSurface.BUBBLE
        )
        scheduler.advanceUntilIdle()
        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any())
        }
    }

    @Test
    fun privacyGuardHideRevokesBubbleSessionWithoutFlush() {
        // Review fix 11: a PRIVACY_GUARD hide reports secure/excluded
        // discovery - a security-equivalent exit that revokes FIRST so the
        // pending edit never flushes (regular hides keep flushing).
        stubLiveLearnPath()
        showBubbleFor(node)
        assertTrue(AutoLearnSessionManager.armBubbleSession(
            field = bubbleSnapshot,
            windowId = 9L,
            injectedText = "shunade",
            confirmedFieldText = "I went to the shunade",
            context = context
        ))
        AutoLearnSessionManager.onTextUpdated(
            "I went to the Sinead", context, bubbleSnapshot, origin = AutoLearnSurface.BUBBLE
        )
        BubbleController.hideBubble(CancelReason.PRIVACY_GUARD)
        scheduler.advanceUntilIdle()
        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any())
        }
    }

    @Test
    fun bubbleHideCancelsPendingArmReadback() {
        // Review fix 15: the arm read-back is a tracked job - a hide during
        // the retry window cancels it, so a delayed successful read can no
        // longer arm a session the bubble already buried.
        stubLiveLearnPath()
        pasteDeliveredPath()
        textState = "Report for "
        showBubbleFor(node)
        BubbleController.injectText(context, "the shunade project ")
        // Attempt 1 misses; attempt 2 (+250 ms) is now pending.
        scheduler.runCurrent()
        BubbleController.hideBubble()
        textState = "Report for the shunade project "
        scheduler.advanceUntilIdle()
        // No session was armed, so the later correction records nothing.
        AutoLearnSessionManager.onTextUpdated(
            "Report for the Sinead project", context, bubbleSnapshot, origin = AutoLearnSurface.BUBBLE
        )
        scheduler.advanceUntilIdle()
        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any())
        }
    }
}
