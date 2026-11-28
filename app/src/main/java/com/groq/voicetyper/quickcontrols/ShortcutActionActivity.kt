package com.groq.voicetyper.quickcontrols

import android.app.Activity
import android.os.Bundle
import com.groq.voicetyper.FluenceAccessibilityService
import com.groq.voicetyper.MainActivity
import com.groq.voicetyper.history.HistoryRepository
import com.groq.voicetyper.isAccessibilityServiceEnabled
import com.groq.voicetyper.rescueTranscriptToClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transparent, no-UI trampoline activity invoked by Fluence's home-screen
 * ("3D Touch") app shortcuts. Validates the per-install shortcut token before
 * executing any action so third-party apps cannot spoof shortcut intents.
 */
class ShortcutActionActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val token = intent?.getStringExtra(FluenceQuickControls.EXTRA_SHORTCUT_TOKEN)
        if (!FluenceQuickControls.isValidShortcutToken(this, token)) {
            finishWithoutAnimation()
            return
        }

        when (intent?.action) {
            FluenceQuickControls.ACTION_TOGGLE_BUBBLE -> {
                handleToggleBubble()
                finishWithoutAnimation()
            }

            FluenceQuickControls.ACTION_TOGGLE_MODE -> {
                handleToggleMode()
                finishWithoutAnimation()
            }

            FluenceQuickControls.ACTION_COPY_LAST -> {
                handleCopyLastDictation()
            }

            else -> finishWithoutAnimation()
        }
    }

    private fun handleToggleBubble() {
        val enabled = isAccessibilityServiceEnabled(this, FluenceAccessibilityService::class.java)
        if (enabled) {
            when (FluenceQuickControls.pauseBubbleForPayments(this)) {
                FluenceQuickControls.PauseBubbleOutcome.PAUSED_PROGRAMMATICALLY -> {
                    FluenceQuickControls.showToast(
                        this,
                        "Fluence Bubble paused. Voice Keyboard still works.",
                        long = true
                    )
                }

                FluenceQuickControls.PauseBubbleOutcome.OPENED_SETTINGS_FALLBACK -> {
                    FluenceQuickControls.showToast(
                        this,
                        "Turn off Fluence On-Screen Dictation to pay",
                        long = true
                    )
                }

                FluenceQuickControls.PauseBubbleOutcome.ALREADY_PAUSED -> {
                    openAccessibilityForResume()
                }
            }
        } else {
            openAccessibilityForResume()
        }
    }

    private fun openAccessibilityForResume() {
        FluenceQuickControls.showToast(
            this,
            "Turn on Fluence On-Screen Dictation",
            long = true
        )
        if (!FluenceQuickControls.openAccessibilitySettings(this)) {
            FluenceQuickControls.showToast(this, "Could not open accessibility settings")
        }
    }

    private fun handleToggleMode() {
        when (val result = FluenceQuickControls.toggleOfflineMode(this)) {
            FluenceQuickControls.ModeToggleResult.SwitchedToCloud -> {
                FluenceQuickControls.showToast(this, "Switched to Cloud dictation")
            }

            is FluenceQuickControls.ModeToggleResult.SwitchedToOffline -> {
                FluenceQuickControls.showToast(
                    this,
                    "Switched to Offline (${result.engine.displayName})"
                )
            }

            FluenceQuickControls.ModeToggleResult.NoModelDownloaded -> {
                FluenceQuickControls.showToast(
                    this,
                    "Download an offline model first",
                    long = true
                )
                runCatching {
                    startActivity(
                        FluenceQuickControls.createDeepLinkIntent(
                            this,
                            MainActivity.ROUTE_OFFLINE_CONFIG
                        )
                    )
                }
            }
        }
    }

    private fun handleCopyLastDictation() {
        // Keep this transparent activity focused for the brief Room read so
        // Android 10+ OEM clipboard policies always allow setPrimaryClip().
        scope.launch {
            try {
                val latest = withContext(Dispatchers.IO) {
                    HistoryRepository.getLatestText(applicationContext)
                }
                if (latest.isNullOrEmpty()) {
                    FluenceQuickControls.showToast(
                        this@ShortcutActionActivity,
                        "No recent dictation to copy"
                    )
                    runCatching {
                        startActivity(
                            FluenceQuickControls.createDeepLinkIntent(
                                this@ShortcutActionActivity,
                                MainActivity.ROUTE_HISTORY
                            )
                        )
                    }
                } else {
                    val copied = rescueTranscriptToClipboard(this@ShortcutActionActivity, latest)
                    FluenceQuickControls.showToast(
                        this@ShortcutActionActivity,
                        if (copied) "Copied last dictation" else "Could not copy to clipboard"
                    )
                }
            } finally {
                finishWithoutAnimation()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun finishWithoutAnimation() {
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
