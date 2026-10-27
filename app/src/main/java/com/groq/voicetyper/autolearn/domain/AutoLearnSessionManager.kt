package com.groq.voicetyper.autolearn.domain

import android.content.Context
import android.util.Log
import android.view.inputmethod.EditorInfo
import com.groq.voicetyper.PrivacyPreferences
import com.groq.voicetyper.autolearn.AutoLearnPreferences
import com.groq.voicetyper.autolearn.SuggestionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object AutoLearnSessionManager {
    private const val TAG = "AutoLearnSession"
    private const val MAX_FIELD_CHARS = 10_000

    /**
     * Settle gate delay, the event-model translation of Windows
     * monitor.rs `SETTLED_POLLS` (= 2 consecutive identical reads) at the
     * fast cadence `POLL_FAST_MS` = 250 ms: classify a value only after the
     * observation burst stopped changing for 2 x 250 ms. The Windows doc on
     * the constant names the exact defect this prevents - "extracting from
     * those [intermediate reads] would turn each keystroke into its own
     * candidate" (monitor.rs:77-82).
     */
    private const val SETTLE_DELAY_MS = 500L

    /**
     * Windows monitor timeout parity (monitor.rs:371-376): a session the
     * closing surface never closes still ends as a REGULAR close (the
     * final diff is not skipped - FocusChanged/Timeout flush parity) and
     * never outlives `MONITOR_TIMEOUT_MS`.
     */
    private const val SESSION_TIMEOUT_MS = 30_000L

    /**
     * Test seam: when set, the session scope runs on this dispatcher
     * instead of Dispatchers.Default (tests inject test dispatchers).
     * Must be reset to null after each test in @After.
     */
    @Volatile
    internal var dispatcherOverride: CoroutineDispatcher? = null

    /**
     * Test seam: overrides [SETTLE_DELAY_MS]. Tests that exercise the
     * synchronous pipeline use 0; burst-collapse and flush tests use the
     * real delay with a virtual-time scheduler.
     */
    @Volatile
    internal var settleDelayOverride: Long? = null

    /** Test seam: overrides [SESSION_TIMEOUT_MS], reset to null in @After. */
    @Volatile
    internal var timeoutDelayOverride: Long? = null

    @Volatile
    private var activeCommittedText: String? = null

    @Volatile
    private var isPrivacyAllowed: Boolean = false

    @Volatile
    private var targetPackage: String? = null

    /**
     * Session origin identity (review requirement: target/session identity
     * must be explicit across IME and accessibility paths). A teardown hook
     * of one surface can never exit-flush another surface's pending state;
     * security exits remain origin-agnostic.
     */
    @Volatile
    private var sessionOrigin: AutoLearnSurface = AutoLearnSurface.IME

    /** Hosting window of the observed node (bubble surface); null for IME sessions. */
    @Volatile
    private var sessionWindowId: Long? = null

    /** Captured at [startSession] so [endSession]'s exit diff keeps a context without changing the public signatures. */
    @Volatile
    private var sessionContext: Context? = null

    /**
     * Per-session scope (review round 2): each observation session gets its
     * own supervised scope and [endSession] cancels it, so in-flight
     * settle jobs from a previous session abort at their next rejection or
     * suspension point instead of writing after endSession or leaking into
     * the next session. [SupervisorJob] keeps one job's failure from
     * tearing down siblings; failures themselves are caught fail-open below
     * (an IME process must not die on a DAO error).
     */
    @Volatile
    private var sessionScope: CoroutineScope? = null

    /**
     * Windows monitor session dedup (monitor.rs sensor stutter seam): a
     * canonical pair key is counted at most once per observation session;
     * cursor moves within one session must not inflate frequency. Cleared
     * whenever a session starts or ends. [sessionGeneration] bumps on every
     * such clear so in-flight batch jobs from a previous session abort
     * instead of writing after endSession or polluting the next
     * session's key set.
     */
    private val pairLock = Any()
    private val sessionPairKeys = HashSet<String>()
    private var sessionGeneration: Long = 0

    /**
     * Settle gate state, mirroring the monitor.rs loop variables
     * (`pending_value`, `stable_polls`, `last_extracted_value`). IME model:
     * every non-blank, privacy-allowed content update re-arms a settle job;
     * each new update cancels the previous one, and [settleEpoch] advances
     * so a late-firing job classifies nothing superseded by a newer arm.
     */
    private val settleLock = Any()
    private var settleJob: Job? = null
    private var settleEpoch: Long = 0
    @Volatile
    private var settlePendingText: String? = null
    @Volatile
    private var lastExtractedText: String? = null

    private fun generation(): Long = synchronized(pairLock) { sessionGeneration }

    private fun settleDelayMs(): Long = settleDelayOverride ?: SETTLE_DELAY_MS

    private fun timeoutDelayMs(): Long = timeoutDelayOverride ?: SESSION_TIMEOUT_MS

    /**
     * Settle-epoch revalidation (review round 4): the dictionary lookup and
     * extraction between the arm and the write are not interruption points,
     * so when a newer keystroke cancels this arm it also bumps the epoch;
     * a stale in-flight observation aborts here instead of recording an
     * intermediate candidate the settle gate already threw away.
     * [armedEpoch] is null for contexts outside the settle gate.
     */
    private fun isStillArmed(armedEpoch: Long?): Boolean {
        val expected = armedEpoch ?: return true
        return synchronized(settleLock) { settleEpoch == expected }
    }

    private fun resetSessionStateLocked() {
        synchronized(pairLock) {
            sessionPairKeys.clear()
            sessionGeneration += 1
        }
    }

    fun onStartInput(info: EditorInfo?, context: Context) {
        targetPackage = info?.packageName?.toString()
        val masterEnabled = AutoLearnPreferences.isAutoLearnEnabled(context)
        isPrivacyAllowed = masterEnabled &&
            AutoLearnPrivacyHelper.isAutoLearnAllowed(info) &&
            !PrivacyPreferences.isPackageExcluded(context, targetPackage)

        if (!isPrivacyAllowed) {
            // Security-equivalent exit (Windows SecureFieldDetected /
            // ReadOnlyField parity): the session ends with the privacy gate
            // down, so [endSession] skips the exit diff.
            endSession()
        }
    }

    fun startSession(committedText: String, context: Context) {
        if (!isPrivacyAllowed || committedText.isBlank()) {
            endSession()
            return
        }
        beginSession(AutoLearnSurface.IME, baseline = committedText.trim(), windowId = null, sessionPackage = null, context)
    }

    /**
     * Bubble-surface arming (review-design requirement, coverage A): the
     * accessibility surface owns no EditorInfo, so the field rules arrive as
     * an already derived [AutoLearnFieldSnapshot]. Arming happens only AFTER
     * the controller confirmed the injection landed, mirroring the Windows
     * monitor init exactly (monitor.rs:234-273): the baseline read must
     * contain the injected text, and the baseline is the FULL post-paste
     * field (Windows initial_value / last_extracted_value parity,
     * monitor.rs:347-354). Every failure is fail-closed: no arm, no session,
     * no supersede of whatever may still be running.
     */
    fun armBubbleSession(
        field: AutoLearnFieldSnapshot,
        windowId: Long?,
        injectedText: String,
        confirmedFieldText: String,
        context: Context
    ): Boolean {
        // Unlike the IME surface no onStartInput ran for this arm, so the
        // master toggle is re-checked here.
        if (!AutoLearnPreferences.isAutoLearnEnabled(context)) return denyArm("master toggle off")
        if (!field.isObservationAllowedRest()) {
            return denyArm("field rules deny (password/null-type/no-learning unknown or set)")
        }
        val pkg = field.packageName
        if (pkg.isNullOrBlank()) return denyArm("unknown target package")
        if (PrivacyPreferences.isPackageExcluded(context, pkg)) return denyArm("package excluded")
        if (injectedText.isBlank()) return denyArm("blank dictation")
        if (confirmedFieldText.isBlank()) return denyArm("no confirmed post-paste field read")
        // FieldTooLarge parity (monitor.rs:244-256).
        if (confirmedFieldText.length > MAX_FIELD_CHARS) return denyArm("confirmed field too large")
        // InjectedTextNotObserved parity (monitor.rs:257-273): arm only on a
        // confirmed post-paste read that contains the exact injected text.
        if (!confirmedFieldText.contains(injectedText)) {
            return denyArm("injected text not observed in confirmed field")
        }
        beginSession(
            AutoLearnSurface.BUBBLE,
            baseline = confirmedFieldText.trim(),
            windowId = windowId,
            sessionPackage = pkg,
            context = context
        )
        return true
    }

    private fun denyArm(reason: String): Boolean {
        Log.d(TAG, "auto-learn arm denied: $reason")
        return false
    }

    /**
     * [sessionPackage] is only set for bubble sessions (the IME surface
     * derives it from onStartInput and keeps ownership); it becomes
     * [targetPackage] so the per-update package-drift revalidation has a
     * session-identity to compare against.
     */
    private fun beginSession(
        origin: AutoLearnSurface,
        baseline: String,
        windowId: Long?,
        sessionPackage: String?,
        context: Context
    ) {
        if (sessionPackage != null) targetPackage = sessionPackage
        // A new session owns the field now (Windows Superseded parity): the
        // previous session's pending input is dropped without an exit diff.
        synchronized(settleLock) {
            settleEpoch += 1
            settleJob?.cancel()
            settleJob = null
            settlePendingText = null
            lastExtractedText = baseline
        }
        activeCommittedText = baseline
        // The session begins privacy-UP: both callers guarantee the field
        // passed the gate, and a previous security exit leaves this flag
        // false until here - an armed session must always observe, and a
        // later close must always be able to run its regular flush.
        isPrivacyAllowed = true
        sessionOrigin = origin
        sessionWindowId = windowId
        sessionContext = context
        disposeSessionScope()
        resetSessionStateLocked()
        sessionScope = newSessionScope()
        scheduleSessionTimeout()
        Log.d(TAG, "Started observation session ($origin)")
    }

    /** Windows timeout parity (monitor.rs:371-376): the session's own regular close. */
    private fun scheduleSessionTimeout() {
        val scope = sessionScope ?: return
        scope.launch {
            delay(timeoutDelayMs())
            endSessionInternal(requestingOrigin = null)
        }
    }

    fun onTextUpdated(
        currentTextAroundCursor: String,
        context: Context,
        field: AutoLearnFieldSnapshot? = null
    ) {
        // Windows SecureFieldDetected per-read parity (monitor.rs:502-505):
        // every observed read carries the current field identity and
        // positive downgrade evidence exits WITHOUT the exit diff -
        // whatever surface owns the session (security exits are
        // origin-agnostic). Unknown metadata does NOT exit a running
        // session (no positive evidence); the fail-closed rule governs
        // arming, not mid-flight observation.
        if (field != null) {
            val packageMismatch = !field.packageName.isNullOrBlank() &&
                targetPackage != null &&
                field.packageName != targetPackage
            val packageExcluded = PrivacyPreferences.isPackageExcluded(
                context, field.packageName ?: targetPackage
            )
            if (field.isPassword == true || field.isNullInputType == true ||
                field.noPersonalizedLearning == true || packageMismatch || packageExcluded
            ) {
                // Revoke FIRST so the close below is a security-equivalent
                // exit and the final-diff flush stays suppressed.
                isPrivacyAllowed = false
                endSession()
                return
            }
        }
        if (PrivacyPreferences.isPackageExcluded(context, targetPackage)) {
            // Revoke FIRST so the close below is a security-equivalent exit
            // and the final-diff flush stays suppressed.
            isPrivacyAllowed = false
            endSession()
            return
        }

        val committed = activeCommittedText ?: return
        if (!isPrivacyAllowed) return
        if (currentTextAroundCursor.isBlank()) {
            // Windows NoValue parity (monitor.rs:466-478): an empty field is
            // never classified. Cancel the armed observation so a close
            // after clearing cannot flush it as the final diff
            // (monitor.rs:523-528 trusts only a readable last state).
            synchronized(settleLock) {
                if (settlePendingText != null) {
                    settleEpoch += 1
                    settleJob?.cancel()
                    settleJob = null
                    settlePendingText = null
                }
            }
            return
        }

        if (committed.length > MAX_FIELD_CHARS || currentTextAroundCursor.length > MAX_FIELD_CHARS) {
            endSession()
            return
        }

        val scope = sessionScope ?: return

        // Settle gate (monitor.rs:416-433): a value that returned to the
        // already-classified state cancels the pending diff; any other
        // change re-arms the settle timer and cancels the previous arm.
        var armedEpoch: Long? = null
        synchronized(settleLock) {
            if (currentTextAroundCursor == lastExtractedText) {
                settleEpoch += 1
                settleJob?.cancel()
                settleJob = null
                settlePendingText = null
            } else {
                settleEpoch += 1
                settlePendingText = currentTextAroundCursor
                armedEpoch = settleEpoch
                settleJob?.cancel()
            }
        }
        val epoch = armedEpoch
        if (epoch != null) {
            settleJob = scope.launch { settleObservation(context, committed, epoch) }
        }
    }

    private suspend fun settleObservation(context: Context, committed: String, armedEpoch: Long) {
        try {
            delay(settleDelayMs())
            val target: String = synchronized(settleLock) {
                if (settleEpoch != armedEpoch) null else settlePendingText
            } ?: return
            if (target == lastExtractedText) return

            val gen = generation()
            val known = SuggestionRepository.knownPairKeys(context)
            val corrections = DecisionCore.extractCorrections(
                originalText = committed,
                editedRegion = target,
                knownPairKeys = known
            )
            for (candidate in corrections) {
                if (!recordCandidate(context, gen, known, candidate, armedEpoch)) return
            }
            // Windows monitor.rs:462: the classified value becomes the new
            // extraction baseline even when nothing was saved, so the exit
            // diff and later identical updates never re-save it.
            synchronized(settleLock) {
                if (settleEpoch == armedEpoch) {
                    settlePendingText = null
                    lastExtractedText = target
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fail-open: a DAO failure (disk full, corrupt DB...) must
            // never crash the IME process (review round 2, must-fix 2).
            Log.w(TAG, "auto-learn observation aborted", e)
        }
    }

    /**
     * One candidate's guards + write. Returns false when the session
     * flipped (caller must stop the whole loop), true when it may continue
     * with the next candidate.
     */
    private suspend fun recordCandidate(
        context: Context,
        gen: Long,
        known: Set<String>,
        candidate: CorrectionCandidate,
        armedEpoch: Long?
    ): Boolean {
        if (generation() != gen || !isPrivacyAllowed) return false
        // A newer arm superseded this observation while the lookup or
        // extraction ran (review round 4) - don't record it.
        if (!isStillArmed(armedEpoch)) return false

        val key = DecisionCore.canonicalPairKey(candidate.spokenText, candidate.correctedText)
        // Dedup atomically WITH the validity re-check (review round 2,
        // must-fix 1): the insert and the generation check share the lock so
        // an endSession racing in between cannot add a stale key to the
        // freshly cleared next-session set. Residual window (review round 4):
        // after the rechecks below, the suspending DB write begins on this
        // coroutine; a privacy flip landing inside that step is tolerated -
        // [endSession] cancels this scope so the write aborts at its next
        // suspension point, and the generation checks prevent any
        // cross-session carry-over.
        val fresh: Boolean = synchronized(pairLock) {
            if (sessionGeneration != gen || !isPrivacyAllowed) null
            else sessionPairKeys.add(key)
        } ?: return false
        if (!fresh) return true
        // Last cheap rejection before the (suspending) DB write.
        if (generation() != gen || !isPrivacyAllowed || !isStillArmed(armedEpoch)) return false

        try {
            SuggestionRepository.recordCorrectionCandidate(
                context = context,
                knownPairKeys = known,
                spokenText = candidate.spokenText,
                correctedText = candidate.correctedText
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fail-open AND release the dedup key so a later legitimate
            // observation can retry the write (review round 2, must-fix 2).
            Log.w(TAG, "failed to record correction candidate", e)
            synchronized(pairLock) { sessionPairKeys.remove(key) }
        }
        return true
    }

    fun endSession(origin: AutoLearnSurface = AutoLearnSurface.IME) {
        endSessionInternal(requestingOrigin = origin)
    }

    /**
     * [requestingOrigin] = null marks the session's own authoritative
     * close (timeout) or a cross-surface security exit: the flush runs when
     * privacy is still up. A REGULAR close from the OTHER surface cannot
     * flush pending state that surface could not observe (review
     * requirement: AOSP `InputMethodService.doFinishInput()` finishes views
     * BEFORE the next target starts, so the next field's security posture
     * is unknowable at that moment - Windows closes the session it
     * observes, not another session).
     */
    private fun endSessionInternal(requestingOrigin: AutoLearnSurface?) {
        // Windows final diff (monitor.rs:515-548): a regular close with
        // un-settled observed input classifies the final state once
        // (FocusChanged/Timeout parity). Security/privacy-equivalent exits
        // arrive here with [isPrivacyAllowed] already false and skip the
        // flush, exactly like Windows error exits; already-classified or
        // unchanged states flush nothing (Windows last_value !=
        // last_extracted_value guard).
        val flushTarget: String? = synchronized(settleLock) {
            val committed = activeCommittedText
            val pending = settlePendingText
            val originAllowsFlush =
                requestingOrigin == null || requestingOrigin == sessionOrigin
            if (isPrivacyAllowed && originAllowsFlush &&
                committed != null && pending != null && pending != lastExtractedText
            ) {
                pending
            } else {
                null
            }
        }
        synchronized(settleLock) {
            settleJob?.cancel()
            settleJob = null
        }

        if (flushTarget != null) {
            val flushCommitted = activeCommittedText
            val context = sessionContext
            if (flushCommitted != null && context != null) {
                val sessionKeys = synchronized(pairLock) { HashSet(sessionPairKeys) }
                launchFinalDiffFlush(context, flushCommitted, flushTarget, sessionKeys)
            }
        }

        disposeSessionScope()
        if (activeCommittedText != null) {
            Log.d(TAG, "Ended observation session")
            activeCommittedText = null
        }
        targetPackage = null
        isPrivacyAllowed = false
        sessionOrigin = AutoLearnSurface.IME
        sessionWindowId = null
        synchronized(settleLock) {
            settlePendingText = null
            lastExtractedText = null
        }
        resetSessionStateLocked()
    }

    /**
     * Windows final-diff parity (monitor.rs:515-548): by definition this
     * runs after the session decided to close, so it deliberately bypasses
     * the live-session revalidation [recordCandidate] applies. Privacy was
     * checked at the flush decision; the pair dedup carries the called-off
     * session's keys as a flush-local copy (monitor.rs:526-529 filters the
     * exit diff through the same session memory), so it can never pollute
     * or be blocked by the next session's dedup set.
     */
    private fun launchFinalDiffFlush(
        context: Context,
        committed: String,
        target: String,
        recordedKeys: HashSet<String>
    ) {
        CoroutineScope(SupervisorJob() + (dispatcherOverride ?: Dispatchers.Default)).launch {
            try {
                val known = SuggestionRepository.knownPairKeys(context)
                val corrections = DecisionCore.extractCorrections(
                    originalText = committed,
                    editedRegion = target,
                    knownPairKeys = known
                )
                for (candidate in corrections) {
                    val key = DecisionCore.canonicalPairKey(
                        candidate.spokenText,
                        candidate.correctedText
                    )
                    if (!recordedKeys.add(key)) continue
                    try {
                        SuggestionRepository.recordCorrectionCandidate(
                            context = context,
                            knownPairKeys = known,
                            spokenText = candidate.spokenText,
                            correctedText = candidate.correctedText
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "failed to record correction candidate (final diff)", e)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "final diff flush aborted", e)
            }
        }
    }

    private fun disposeSessionScope() {
        sessionScope?.cancel()
        sessionScope = null
    }

    private fun newSessionScope(): CoroutineScope {
        val dispatcher = dispatcherOverride ?: Dispatchers.Default
        return CoroutineScope(SupervisorJob() + dispatcher)
    }
}
