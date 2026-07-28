package com.groq.voicetyper

import android.Manifest
import android.content.Context
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
import androidx.compose.runtime.getValue
import com.groq.voicetyper.navigation.FluenceNavHost
import com.groq.voicetyper.theme.*

class MainActivity : ComponentActivity() {

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

        val updateManager = com.groq.voicetyper.update.UpdateManager.getInstance(this)
        updateManager.onAppStart()

        setContent {
            FluenceTranscribeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppBackground
                ) {
                    val updateViewModel: com.groq.voicetyper.update.UpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                    val updateState by updateViewModel.updateState.collectAsState()

                    FluenceNavHost(
                        onRequestPermission = {
                            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    )

                    com.groq.voicetyper.update.ui.UpdateDialogHost(
                        updateState = updateState,
                        canInstallPackages = updateViewModel.canInstallPackages,
                        onStartDownload = { availableState -> updateViewModel.startDownload(availableState) },
                        onCancelDownload = { updateViewModel.cancelDownload() },
                        onInstall = { readyState -> updateViewModel.installUpdate(readyState) },
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
