package com.groq.voicetyper.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    val updateManager: UpdateManager = UpdateManager.getInstance(application)
    val updateState: StateFlow<UpdateState> = updateManager.updateState

    val preferences: UpdatePreferences
        get() = updateManager.preferences

    val canInstallPackages: Boolean
        get() = updateManager.installManager.canInstallPackages()

    fun checkForUpdates(force: Boolean = true) {
        updateManager.checkForUpdates(force = force)
    }

    fun startDownload(availableState: UpdateState.UpdateAvailable) {
        updateManager.startDownload(availableState)
    }

    fun cancelDownload() {
        updateManager.cancelDownload()
    }

    fun installUpdate(readyState: UpdateState.ReadyToInstall): Boolean {
        return updateManager.installUpdate(readyState)
    }

    fun skipVersion(versionCode: Int) {
        updateManager.skipVersion(versionCode)
    }

    fun remindMeLater() {
        updateManager.remindMeLater()
    }

    fun resetState() {
        updateManager.resetState()
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        preferences.autoCheckEnabled = enabled
    }
}
