package com.groq.voicetyper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.groq.voicetyper.navigation.FluenceNavHost
import com.groq.voicetyper.sync.SyncManager
import com.groq.voicetyper.sync.SyncSchedule
import com.groq.voicetyper.sync.auth.GoogleOAuth
import com.groq.voicetyper.sync.auth.SyncAuthSession
import com.groq.voicetyper.theme.*

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_DEEP_LINK_SETTINGS = "deep_link_settings"
    }

    private var deepLinkToSettings by mutableStateOf(false)

    private val syncManager by lazy {
        SyncManager(
            context = applicationContext,
            auth = SyncAuthSession(applicationContext),
            scope = lifecycleScope,
        )
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Microphone permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Microphone permission is required for voice typing", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        deepLinkToSettings = intent.getBooleanExtra(EXTRA_DEEP_LINK_SETTINGS, false)

        val updateManager = com.groq.voicetyper.update.UpdateManager.getInstance(this)
        updateManager.onAppStart()

        // Sync (spec §27 phase 8): enqueue the background works once and run
        // the foreground poll loop while the activity is started.
        SyncSchedule.enqueuePeriodic(applicationContext)
        com.groq.voicetyper.sync.SyncAccounts.refresh(applicationContext)
        lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                when (event) {
                    Lifecycle.Event.ON_START -> syncManager.start()
                    Lifecycle.Event.ON_STOP -> syncManager.stop()
                    else -> {}
                }
            }
        })

        setContent {
            FluenceTranscribeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppBackground
                ) {
                    val updateViewModel: com.groq.voicetyper.update.UpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                    val updateState by updateViewModel.updateState.collectAsState()
                    var canInstallPackages by remember { mutableStateOf(updateViewModel.canInstallPackages) }
                    val lifecycleOwner = LocalLifecycleOwner.current

                    LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            canInstallPackages = updateViewModel.canInstallPackages
                        }
                    }.let { observer ->
                        DisposableEffect(lifecycleOwner) {
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                        }
                    }

                    FluenceNavHost(
                        syncManager = syncManager,
                        deepLinkToSettings = deepLinkToSettings,
                        onRequestPermission = {
                            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onSignInClick = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val tokens = GoogleOAuth.signInWithLoopback(applicationContext)
                                    val email = GoogleOAuth.fetchAccountEmail(GoogleOAuth.newHttpClient(), tokens.accessToken)
                                    syncManager.completeSignIn(tokens, email)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@MainActivity, "Signed in as $email", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("FluenceAuth", "signInWithLoopback failed", e)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@MainActivity, "Sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        onSignOutClick = {
                            syncManager.signOut()
                        }
                    )

                    com.groq.voicetyper.update.ui.UpdateDialogHost(
                        updateState = updateState,
                        canInstallPackages = canInstallPackages,
                        onStartDownload = { availableState -> updateViewModel.startDownload(availableState) },
                        onCancelDownload = { updateViewModel.cancelDownload() },
                        onInstall = { readyState ->
                            if (!updateViewModel.installUpdate(readyState)) {
                                updateViewModel.reportError(
                                    "Unable to start installation. Make sure 'Install unknown apps' is allowed, then try again."
                                )
                            }
                        },
                        onSkipVersion = { versionCode -> updateViewModel.skipVersion(versionCode) },
                        onRemindMeLater = { updateViewModel.remindMeLater() },
                        onRetry = { updateViewModel.checkForUpdates(force = true) },
                        onDismissError = { updateViewModel.resetState() },
                        onRequestInstallPermission = {
                            try {
                                startActivity(updateManager.installManager.createInstallPermissionIntent())
                            } catch (_: Exception) {}
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_DEEP_LINK_SETTINGS, false)) {
            deepLinkToSettings = true
        }
    }
}

fun Modifier.glassCard(shape: RoundedCornerShape = RoundedCornerShape(24.dp)): Modifier = this
    .background(
        color = Panel,
        shape = shape
    )
    .border(
        width = 1.dp,
        color = OutlineSubtle,
        shape = shape
    )

fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<out android.accessibilityservice.AccessibilityService>): Boolean {
    val expectedComponentName = android.content.ComponentName(context, serviceClass)
    val enabledServices = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServices)
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledService = android.content.ComponentName.unflattenFromString(componentNameString)
        if (enabledService != null && enabledService == expectedComponentName) {
            return true
        }
    }
    return false
}
