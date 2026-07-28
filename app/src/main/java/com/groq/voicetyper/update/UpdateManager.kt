package com.groq.voicetyper.update

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.groq.voicetyper.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(
        val metadata: ReleaseMetadata,
        val releaseNotes: String,
        val apkDownloadUrl: String,
        val releaseUrl: String
    ) : UpdateState()

    data class Downloading(
        val progressPercent: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : UpdateState()

    data class ReadyToInstall(
        val apkFile: File,
        val metadata: ReleaseMetadata
    ) : UpdateState()

    data class Error(val message: String) : UpdateState()
    object UpToDate : UpdateState()
}

class UpdateManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val repository = GitHubUpdateRepository(appContext)
    val preferences = UpdatePreferences(appContext)
    val installManager = PackageInstallManager(appContext)
    private val workManager = WorkManager.getInstance(appContext)

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    init {
        performPostInstallCleanup()
    }

    fun onAppStart() {
        if (!preferences.autoCheckEnabled) return
        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - preferences.lastCheckedTimestamp
        if (elapsed >= CHECK_INTERVAL_MS) {
            checkForUpdates(force = false)
        }
    }

    fun checkForUpdates(force: Boolean = false) {
        if (_updateState.value is UpdateState.Checking || _updateState.value is UpdateState.Downloading) {
            return
        }

        _updateState.value = UpdateState.Checking

        scope.launch {
            when (val result = repository.checkForUpdate(forceCheck = force)) {
                is UpdateCheckResult.UpdateAvailable -> {
                    if (!force && result.metadata.versionCode == preferences.skippedVersionCode) {
                        _updateState.value = UpdateState.UpToDate
                    } else {
                        _updateState.value = UpdateState.UpdateAvailable(
                            metadata = result.metadata,
                            releaseNotes = result.releaseNotes,
                            apkDownloadUrl = result.apkDownloadUrl,
                            releaseUrl = result.releaseUrl
                        )
                    }
                }
                is UpdateCheckResult.UpToDate, is UpdateCheckResult.NotModified -> {
                    _updateState.value = UpdateState.UpToDate
                }
                is UpdateCheckResult.Error -> {
                    _updateState.value = UpdateState.Error(result.message)
                }
            }
        }
    }

    private var currentObserver: androidx.lifecycle.Observer<WorkInfo>? = null

    fun startDownload(availableState: UpdateState.UpdateAvailable) {
        val inputData = Data.Builder()
            .putString(ApkDownloadWorker.KEY_DOWNLOAD_URL, availableState.apkDownloadUrl)
            .putLong(ApkDownloadWorker.KEY_EXPECTED_SIZE, availableState.metadata.apkSize)
            .putString(ApkDownloadWorker.KEY_EXPECTED_SHA256, availableState.metadata.sha256)
            .build()

        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()

        val downloadWorkRequest = OneTimeWorkRequestBuilder<ApkDownloadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .build()

        _updateState.value = UpdateState.Downloading(
            progressPercent = 0,
            bytesDownloaded = 0L,
            totalBytes = availableState.metadata.apkSize
        )

        workManager.enqueueUniqueWork(
            WORK_NAME_DOWNLOAD_APK,
            ExistingWorkPolicy.REPLACE,
            downloadWorkRequest
        )

        val liveData = workManager.getWorkInfoByIdLiveData(downloadWorkRequest.id)
        currentObserver?.let { liveData.removeObserver(it) }

        val observer = object : androidx.lifecycle.Observer<WorkInfo> {
            override fun onChanged(workInfo: WorkInfo) {
                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress
                        val percent = progress.getInt(ApkDownloadWorker.KEY_PROGRESS_PERCENT, 0)
                        val bytes = progress.getLong(ApkDownloadWorker.KEY_PROGRESS_BYTES, 0L)
                        val total = progress.getLong(ApkDownloadWorker.KEY_TOTAL_BYTES, availableState.metadata.apkSize)

                        _updateState.value = UpdateState.Downloading(
                            progressPercent = percent,
                            bytesDownloaded = bytes,
                            totalBytes = total
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        liveData.removeObserver(this)
                        currentObserver = null
                        preferences.downloadedVersionCode = availableState.metadata.versionCode
                        val apkPath = workInfo.outputData.getString(ApkDownloadWorker.KEY_APK_PATH)
                        if (apkPath != null) {
                            val apkFile = File(apkPath)
                            if (apkFile.exists()) {
                                _updateState.value = UpdateState.ReadyToInstall(apkFile, availableState.metadata)
                            } else {
                                _updateState.value = UpdateState.Error("Downloaded APK file not found")
                            }
                        } else {
                            _updateState.value = UpdateState.Error("Invalid download output data")
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        liveData.removeObserver(this)
                        currentObserver = null
                        val error = workInfo.outputData.getString(ApkDownloadWorker.KEY_ERROR) ?: "Download failed"
                        _updateState.value = UpdateState.Error(error)
                    }
                    WorkInfo.State.CANCELLED -> {
                        liveData.removeObserver(this)
                        currentObserver = null
                        _updateState.value = UpdateState.Idle
                    }
                    else -> {}
                }
            }
        }

        currentObserver = observer
        liveData.observeForever(observer)
    }

    fun cancelDownload() {
        workManager.cancelUniqueWork(WORK_NAME_DOWNLOAD_APK)
        _updateState.value = UpdateState.Idle
    }

    fun installUpdate(readyState: UpdateState.ReadyToInstall): Boolean {
        return installManager.installApk(readyState.apkFile)
    }

    fun skipVersion(versionCode: Int) {
        preferences.skippedVersionCode = versionCode
        _updateState.value = UpdateState.Idle
    }

    fun remindMeLater() {
        preferences.lastCheckedTimestamp = System.currentTimeMillis()
        _updateState.value = UpdateState.Idle
    }

    fun resetState() {
        _updateState.value = UpdateState.Idle
    }

    private fun performPostInstallCleanup() {
        try {
            val updatesDir = File(appContext.filesDir, "updates")
            if (updatesDir.exists() && updatesDir.isDirectory) {
                val tmpFile = File(updatesDir, "app-update.tmp")
                if (tmpFile.exists()) {
                    tmpFile.delete()
                }

                val apkFile = File(updatesDir, "app-update.apk")
                if (apkFile.exists()) {
                    val downloadedVersion = preferences.downloadedVersionCode
                    if (downloadedVersion > 0 && BuildConfig.VERSION_CODE >= downloadedVersion) {
                        apkFile.delete()
                        preferences.resetDownloadedVersion()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    companion object {
        private const val WORK_NAME_DOWNLOAD_APK = "fluence_apk_download_work"
        private val CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(24)

        @Volatile
        private var INSTANCE: UpdateManager? = null

        fun getInstance(context: Context): UpdateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UpdateManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
