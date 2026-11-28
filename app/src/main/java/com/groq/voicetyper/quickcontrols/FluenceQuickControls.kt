package com.groq.voicetyper.quickcontrols

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.groq.voicetyper.FluenceAccessibilityService
import com.groq.voicetyper.MainActivity
import com.groq.voicetyper.R
import com.groq.voicetyper.isAccessibilityServiceEnabled
import com.groq.voicetyper.offline.ModelAssetManager
import com.groq.voicetyper.offline.OfflineEngineType
import com.groq.voicetyper.offline.OfflinePreferences
import com.groq.voicetyper.offline.v2.MoonshineV2ModelManager
import com.groq.voicetyper.offline.v2.MoonshineV2ModelType
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Coordinates Fluence's quick-control surfaces:
 * 1. Dynamic home-screen ("3D Touch") app shortcuts (3 high-value actions)
 * 2. Quick Settings tiles (Fluence Bubble & Fluence Mode)
 * 3. Accessibility pause / highlight-settings helpers
 */
object FluenceQuickControls {
    private const val TAG = "FluenceQuickControls"
    private const val PREFS_NAME = "fluence_prefs"
    private const val KEY_SHORTCUT_TOKEN = "fluence_shortcut_auth_token"

    const val SHORTCUT_ID_BUBBLE = "fluence_bubble_toggle"
    const val SHORTCUT_ID_MODE = "fluence_mode_toggle"
    const val SHORTCUT_ID_COPY_LAST = "fluence_copy_last"

    const val ACTION_TOGGLE_BUBBLE = "com.groq.voicetyper.action.SHORTCUT_TOGGLE_BUBBLE"
    const val ACTION_TOGGLE_MODE = "com.groq.voicetyper.action.SHORTCUT_TOGGLE_MODE"
    const val ACTION_COPY_LAST = "com.groq.voicetyper.action.SHORTCUT_COPY_LAST"

    internal const val EXTRA_SHORTCUT_TOKEN = "com.groq.voicetyper.extra.SHORTCUT_TOKEN"

    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    sealed interface ModeToggleResult {
        data object SwitchedToCloud : ModeToggleResult
        data class SwitchedToOffline(val engine: OfflineEngineType) : ModeToggleResult
        data object NoModelDownloaded : ModeToggleResult
    }

    enum class PauseBubbleOutcome {
        PAUSED_PROGRAMMATICALLY,
        OPENED_SETTINGS_FALLBACK,
        ALREADY_PAUSED
    }

    internal fun getOrCreateShortcutToken(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_SHORTCUT_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing

        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        prefs.edit().putString(KEY_SHORTCUT_TOKEN, generated).apply()
        return generated
    }

    internal fun isValidShortcutToken(context: Context, candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val expected = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SHORTCUT_TOKEN, null)
            ?: return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            candidate.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * Pure engine resolution for switching into Offline mode: prefers the
     * user's currently selected engine if ready, otherwise falls back to any
     * other downloaded engine, or returns null when no offline model is ready.
     */
    internal fun resolveReadyOfflineEngine(
        preferred: OfflineEngineType,
        isReady: (OfflineEngineType) -> Boolean
    ): OfflineEngineType? {
        if (isReady(preferred)) return preferred
        val fallbackOrder = listOf(
            OfflineEngineType.MOONSHINE_V2_SMALL_STREAMING,
            OfflineEngineType.MOONSHINE_V2_MEDIUM_STREAMING,
            OfflineEngineType.SENSEVOICE
        )
        return fallbackOrder.firstOrNull { it != preferred && isReady(it) }
    }

    fun isEngineReadySync(context: Context, engine: OfflineEngineType): Boolean {
        val appCtx = context.applicationContext
        return when (engine) {
            OfflineEngineType.SENSEVOICE -> ModelAssetManager.isModelReadySync(appCtx)
            OfflineEngineType.MOONSHINE_V2_SMALL_STREAMING ->
                MoonshineV2ModelManager.isModelReadySync(appCtx, MoonshineV2ModelType.SMALL)
            OfflineEngineType.MOONSHINE_V2_MEDIUM_STREAMING ->
                MoonshineV2ModelManager.isModelReadySync(appCtx, MoonshineV2ModelType.MEDIUM)
        }
    }

    fun createAccessibilitySettingsIntent(context: Context): Intent {
        val componentName = ComponentName(
            context.applicationContext,
            FluenceAccessibilityService::class.java
        ).flattenToString()
        val showArgs = Bundle().apply {
            putString(EXTRA_FRAGMENT_ARG_KEY, componentName)
        }
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(EXTRA_FRAGMENT_ARG_KEY, componentName)
            putExtra(EXTRA_SHOW_FRAGMENT_ARGS, showArgs)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun openAccessibilitySettings(context: Context): Boolean {
        val highlightedIntent = createAccessibilitySettingsIntent(context)
        val launched = runCatching {
            context.startActivity(highlightedIntent)
        }.isSuccess
        if (launched) return true

        val fallback = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(fallback)
        }.isSuccess
    }

    fun createDeepLinkIntent(context: Context, route: String): Intent {
        return Intent(context.applicationContext, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_DEEP_LINK_ROUTE, route)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
    }

    /**
     * Pauses the Accessibility Service programmatically on Android 7+ via
     * disableSelf(). Falls back to system Accessibility Settings if the service
     * instance is disconnected or the OS refuses disableSelf().
     */
    fun pauseBubbleForPayments(
        context: Context,
        openSettingsOnFallback: Boolean = true
    ): PauseBubbleOutcome {
        val appCtx = context.applicationContext
        if (!isAccessibilityServiceEnabled(appCtx, FluenceAccessibilityService::class.java)) {
            refreshAll(appCtx, accessibilityEnabledOverride = false)
            return PauseBubbleOutcome.ALREADY_PAUSED
        }

        val disabled = FluenceAccessibilityService.requestPauseForPayments()
        return if (disabled) {
            PaymentPauseNotifier.showPausedReminder(appCtx)
            refreshAll(appCtx, accessibilityEnabledOverride = false)
            PauseBubbleOutcome.PAUSED_PROGRAMMATICALLY
        } else {
            if (openSettingsOnFallback) {
                openAccessibilitySettings(context)
            }
            PauseBubbleOutcome.OPENED_SETTINGS_FALLBACK
        }
    }

    fun toggleOfflineMode(context: Context): ModeToggleResult {
        val appCtx = context.applicationContext
        val currentlyOffline = OfflinePreferences.isOfflineModeEnabled(appCtx)
        return if (currentlyOffline) {
            OfflinePreferences.setOfflineModeEnabled(appCtx, false)
            refreshAll(appCtx, offlineModeOverride = false)
            ModeToggleResult.SwitchedToCloud
        } else {
            val preferred = OfflinePreferences.getEngineType(appCtx)
            val readyEngine = resolveReadyOfflineEngine(preferred) { isEngineReadySync(appCtx, it) }
            if (readyEngine == null) {
                ModeToggleResult.NoModelDownloaded
            } else {
                if (readyEngine != preferred) {
                    OfflinePreferences.setEngineType(appCtx, readyEngine)
                }
                OfflinePreferences.setOfflineModeEnabled(appCtx, true)
                refreshAll(appCtx, offlineModeOverride = true)
                ModeToggleResult.SwitchedToOffline(readyEngine)
            }
        }
    }

    fun refreshAll(
        context: Context,
        accessibilityEnabledOverride: Boolean? = null,
        offlineModeOverride: Boolean? = null
    ) {
        val appCtx = context.applicationContext
        refreshDynamicShortcuts(appCtx, accessibilityEnabledOverride, offlineModeOverride)
        requestQuickSettingsTilesUpdate(appCtx)
    }

    fun refreshDynamicShortcuts(
        context: Context,
        accessibilityEnabledOverride: Boolean? = null,
        offlineModeOverride: Boolean? = null
    ) {
        val appCtx = context.applicationContext
        try {
            val a11yEnabled = accessibilityEnabledOverride
                ?: isAccessibilityServiceEnabled(appCtx, FluenceAccessibilityService::class.java)
            val offlineEnabled = offlineModeOverride
                ?: OfflinePreferences.isOfflineModeEnabled(appCtx)
            val token = getOrCreateShortcutToken(appCtx)

            val bubbleShortcut = ShortcutInfoCompat.Builder(appCtx, SHORTCUT_ID_BUBBLE)
                .setShortLabel(
                    appCtx.getString(
                        if (a11yEnabled) R.string.shortcut_pause_bubble_short
                        else R.string.shortcut_resume_bubble_short
                    )
                )
                .setLongLabel(
                    appCtx.getString(
                        if (a11yEnabled) R.string.shortcut_pause_bubble_long
                        else R.string.shortcut_resume_bubble_long
                    )
                )
                .setIcon(
                    IconCompat.createWithResource(
                        appCtx,
                        if (a11yEnabled) R.drawable.ic_shortcut_pause_bubble
                        else R.drawable.ic_shortcut_resume_bubble
                    )
                )
                .setIntent(createShortcutIntent(appCtx, ACTION_TOGGLE_BUBBLE, token))
                .setRank(0)
                .build()

            val modeShortcut = ShortcutInfoCompat.Builder(appCtx, SHORTCUT_ID_MODE)
                .setShortLabel(
                    appCtx.getString(
                        if (offlineEnabled) R.string.shortcut_switch_cloud_short
                        else R.string.shortcut_switch_offline_short
                    )
                )
                .setLongLabel(
                    appCtx.getString(
                        if (offlineEnabled) R.string.shortcut_switch_cloud_long
                        else R.string.shortcut_switch_offline_long
                    )
                )
                .setIcon(
                    IconCompat.createWithResource(
                        appCtx,
                        if (offlineEnabled) R.drawable.ic_shortcut_cloud_mode
                        else R.drawable.ic_shortcut_offline_mode
                    )
                )
                .setIntent(createShortcutIntent(appCtx, ACTION_TOGGLE_MODE, token))
                .setRank(1)
                .build()

            val copyLastShortcut = ShortcutInfoCompat.Builder(appCtx, SHORTCUT_ID_COPY_LAST)
                .setShortLabel(appCtx.getString(R.string.shortcut_copy_last_short))
                .setLongLabel(appCtx.getString(R.string.shortcut_copy_last_long))
                .setIcon(IconCompat.createWithResource(appCtx, R.drawable.ic_shortcut_copy_last))
                .setIntent(createShortcutIntent(appCtx, ACTION_COPY_LAST, token))
                .setRank(2)
                .build()

            val shortcuts = listOf(bubbleShortcut, modeShortcut, copyLastShortcut)
            ShortcutManagerCompat.setDynamicShortcuts(appCtx, shortcuts)
            // Keep any launcher-pinned shortcuts in sync with dynamic state labels/icons.
            ShortcutManagerCompat.updateShortcuts(appCtx, shortcuts)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update dynamic shortcuts", e)
        }
    }

    private fun createShortcutIntent(context: Context, action: String, token: String): Intent {
        return Intent(context, ShortcutActionActivity::class.java).apply {
            this.action = action
            putExtra(EXTRA_SHORTCUT_TOKEN, token)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
    }

    fun requestQuickSettingsTilesUpdate(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val appCtx = context.applicationContext
        runCatching {
            TileService.requestListeningState(
                appCtx,
                ComponentName(appCtx, FluenceBubbleTileService::class.java)
            )
        }
        runCatching {
            TileService.requestListeningState(
                appCtx,
                ComponentName(appCtx, FluenceModeTileService::class.java)
            )
        }
    }

    fun requestAddBubbleQuickSettingsTile(
        context: Context,
        onResult: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onResult("Open Quick Settings and edit tiles to add Fluence Bubble")
            return
        }
        val appCtx = context.applicationContext
        val statusBarManager = appCtx.getSystemService(StatusBarManager::class.java)
        if (statusBarManager == null) {
            onResult("Quick Settings tile request is unavailable on this device")
            return
        }
        runCatching {
            statusBarManager.requestAddTileService(
                ComponentName(appCtx, FluenceBubbleTileService::class.java),
                appCtx.getString(R.string.qs_tile_bubble_label),
                Icon.createWithResource(appCtx, R.drawable.ic_qs_bubble),
                appCtx.mainExecutor
            ) { resultCode ->
                val message = when (resultCode) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                        "Fluence Bubble added to Quick Settings"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                        "Fluence Bubble is already in Quick Settings"
                    else -> null
                }
                if (message != null) {
                    onResult(message)
                }
            }
        }.onFailure {
            Log.w(TAG, "requestAddTileService failed", it)
            onResult("Open Quick Settings and edit tiles to add Fluence Bubble")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    @SuppressLint("StartActivityAndCollapseDeprecated")
    internal fun launchTileActivityAndCollapse(
        tileService: TileService,
        intent: Intent,
        requestCode: Int,
        fallbackToOpenSettings: Boolean = false
    ) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    tileService,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                tileService.startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                tileService.startActivityAndCollapse(intent)
            }
        }.onFailure {
            if (fallbackToOpenSettings) {
                openAccessibilitySettings(tileService)
            }
        }
    }

    fun showToast(context: Context, message: String, long: Boolean = false) {
        val appCtx = context.applicationContext
        mainHandler.post {
            runCatching {
                Toast.makeText(
                    appCtx,
                    message,
                    if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
