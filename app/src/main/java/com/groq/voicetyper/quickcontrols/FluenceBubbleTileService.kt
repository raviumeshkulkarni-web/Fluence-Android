package com.groq.voicetyper.quickcontrols

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.groq.voicetyper.FluenceAccessibilityService
import com.groq.voicetyper.R
import com.groq.voicetyper.isAccessibilityServiceEnabled

/**
 * Quick Settings tile ("Fluence Bubble") letting users pause the Accessibility
 * Service in 1 tap right at checkout without leaving Google Pay or PhonePe, and
 * jump directly to Accessibility Settings (highlighting Fluence) to re-enable.
 */
@RequiresApi(Build.VERSION_CODES.N)
class FluenceBubbleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile(isAccessibilityServiceEnabled(this, FluenceAccessibilityService::class.java))
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { handleClick() }
        } else {
            handleClick()
        }
    }

    private fun handleClick() {
        val enabled = isAccessibilityServiceEnabled(this, FluenceAccessibilityService::class.java)
        if (enabled) {
            when (FluenceQuickControls.pauseBubbleForPayments(this, openSettingsOnFallback = false)) {
                FluenceQuickControls.PauseBubbleOutcome.PAUSED_PROGRAMMATICALLY -> {
                    updateTile(false)
                    FluenceQuickControls.showToast(
                        this,
                        "Fluence Bubble paused. Voice Keyboard still works.",
                        long = true
                    )
                }

                FluenceQuickControls.PauseBubbleOutcome.OPENED_SETTINGS_FALLBACK -> {
                    openAccessibilityFromTile("Turn off Fluence On-Screen Dictation to pay")
                }

                FluenceQuickControls.PauseBubbleOutcome.ALREADY_PAUSED -> {
                    updateTile(false)
                    openAccessibilityFromTile("Turn on Fluence On-Screen Dictation")
                }
            }
        } else {
            openAccessibilityFromTile("Turn on Fluence On-Screen Dictation")
        }
    }

    private fun openAccessibilityFromTile(toastMessage: String) {
        FluenceQuickControls.showToast(this, toastMessage, long = true)
        val intent = FluenceQuickControls.createAccessibilitySettingsIntent(this)
        FluenceQuickControls.launchTileActivityAndCollapse(
            this,
            intent,
            requestCode = 4101,
            fallbackToOpenSettings = true
        )
    }

    private fun updateTile(enabled: Boolean) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.qs_tile_bubble_label)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (enabled) "Active" else "Paused"
        }
        tile.updateTile()
    }
}
