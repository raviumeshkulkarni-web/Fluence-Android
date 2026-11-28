package com.groq.voicetyper.quickcontrols

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.groq.voicetyper.MainActivity
import com.groq.voicetyper.R
import com.groq.voicetyper.offline.OfflinePreferences

/**
 * Quick Settings tile ("Fluence Mode") allowing 1-tap Cloud <-> Offline switching
 * from any screen while the floating bubble stays active, with zero edits to the
 * frozen FloatingBubbleService / FloatingBubbleUI overlays.
 */
@RequiresApi(Build.VERSION_CODES.N)
class FluenceModeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile(OfflinePreferences.isOfflineModeEnabled(this))
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
        when (val result = FluenceQuickControls.toggleOfflineMode(this)) {
            FluenceQuickControls.ModeToggleResult.SwitchedToCloud -> {
                updateTile(false)
                FluenceQuickControls.showToast(this, "Switched to Cloud dictation")
            }

            is FluenceQuickControls.ModeToggleResult.SwitchedToOffline -> {
                updateTile(true)
                FluenceQuickControls.showToast(
                    this,
                    "Switched to Offline (${result.engine.displayName})"
                )
            }

            FluenceQuickControls.ModeToggleResult.NoModelDownloaded -> {
                updateTile(false)
                FluenceQuickControls.showToast(
                    this,
                    "Download an offline model first",
                    long = true
                )
                val intent = FluenceQuickControls.createDeepLinkIntent(
                    this,
                    MainActivity.ROUTE_OFFLINE_CONFIG
                )
                FluenceQuickControls.launchTileActivityAndCollapse(
                    this,
                    intent,
                    requestCode = 4102
                )
            }
        }
    }

    private fun updateTile(offlineEnabled: Boolean) {
        val tile = qsTile ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.label = getString(R.string.qs_tile_mode_label)
            tile.subtitle = if (offlineEnabled) "Offline" else "Cloud"
        } else {
            tile.label = if (offlineEnabled) "Fluence: Offline" else "Fluence: Cloud"
        }
        tile.state = if (offlineEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
