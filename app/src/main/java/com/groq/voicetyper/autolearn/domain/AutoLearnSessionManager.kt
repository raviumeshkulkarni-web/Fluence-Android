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

    @Volatile
    private var activeCommittedText: String? = null

    @Volatile
    private var isPrivacyAllowed: Boolean = false

    @Volatile
    private var targetPackage: String? = null

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

        // A new session owns the field now (Windows Superseded parity): the
        // previous session's pending input is dropped without an exit diff.
        synchronized(settleLock) {
            settleEpoch += 1
            settleJob?.cancel()
            settleJob = null
            settlePendingText = null
            lastExtractedText = committedText.trim()
        }
        activeCommittedText = committedText.trim()
        sessionContext = context
        disposeSessionScope()
        resetSessionStateLocked()
        sessionScope = newSessionScope()
        Log.d(TAG, "Started observation session")
    }

    fun onTextUpdated(currentTextAroundCursor: String, context: Context) {
        if (PrivacyPreferences.isPackageExcluded(context, targetPackage)) {
            // Revoke FIRST so the close below is a security-equivalent exit
            // and the final-diff flush stays suppressed.
            isPrivacyAllowed = false
            endSession()
            return
        }

        val committed = activeCommittedText ?: return
        if (!isPrivacyAllowed || currentTextAroundCursor.isBlank()) return

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
                if (!recordCandidate(context, gen, known, candidate)) return
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
        candidate: CorrectionCandidate
    ): Boolean {
        if (generation() != gen || !isPrivacyAllowed) return false

        val key = DecisionCore.canonicalPairKey(candidate.spokenText, candidate.correctedText)
        // Dedup atomically WITH the validity re-check (review round 2,
        // must-fix 1): the insert and the generation check share the lock so
        // an endSession racing in between cannot add a stale key to the
        // freshly cleared next-session set, and no write can be recorded
        // after the flip.
        val fresh: Boolean = synchronized(pairLock) {
            if (sessionGeneration != gen || !isPrivacyAllowed) null
            else sessionPairKeys.add(key)
        } ?: return false
        if (!fresh) return true
        // Last cheap rejection before the (suspending) DB write.
        if (generation() != gen || !isPrivacyAllowed) return false

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

    fun endSession() {
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
            if (isPrivacyAllowed && committed != null && pending != null && pending != lastExtractedText) {
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
