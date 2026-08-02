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
import kotlinx.coroutines.withContext
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
        reattachDownloadState()
    }

    fun onAppStart() {
        if (!preferences.autoCheckEnabled) return
        // If a completed download is already sitting in ReadyToInstall, don't let
        // the auto-check flip the dialog to Checking/UpdateAvailable (or re-download)
        // for the same pending version.
        if (_updateState.value is UpdateState.ReadyToInstall) return
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
    private var currentLiveData: androidx.lifecycle.LiveData<WorkInfo>? = null

    fun startDownload(availableState: UpdateState.UpdateAvailable) {
        val inputData = Data.Builder()
            .putString(ApkDownloadWorker.KEY_DOWNLOAD_URL, availableState.apkDownloadUrl)
            .putLong(ApkDownloadWorker.KEY_EXPECTED_SIZE, availableState.metadata.apkSize)
            .putString(ApkDownloadWorker.KEY_EXPECTED_SHA256, availableState.metadata.sha256)
            .putInt(ApkDownloadWorker.KEY_VERSION_CODE, availableState.metadata.versionCode)
            .build()

        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(
                if (preferences.allowMeteredDownloads) androidx.work.NetworkType.CONNECTED
                else androidx.work.NetworkType.UNMETERED
            )
            .build()

        val downloadWorkRequest = OneTimeWorkRequestBuilder<ApkDownloadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        preferences.downloadedVersionCode = availableState.metadata.versionCode
        preferences.downloadedVersionName = availableState.metadata.versionName
        preferences.downloadedApkSize = availableState.metadata.apkSize

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

        observeWork(
            workId = downloadWorkRequest.id,
            fallbackMetadata = availableState.metadata,
            defaultTotalBytes = availableState.metadata.apkSize
        )
    }

    private fun observeWork(
        workId: java.util.UUID,
        fallbackMetadata: ReleaseMetadata?,
        defaultTotalBytes: Long
    ) {
        currentObserver?.let { observer -> currentLiveData?.removeObserver(observer) }
        currentObserver = null
        currentLiveData = null

        val liveData = workManager.getWorkInfoByIdLiveData(workId)

        val observer = object : androidx.lifecycle.Observer<WorkInfo> {
            override fun onChanged(workInfo: WorkInfo) {
                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress
                        val percent = progress.getInt(ApkDownloadWorker.KEY_PROGRESS_PERCENT, 0)
                        val bytes = progress.getLong(ApkDownloadWorker.KEY_PROGRESS_BYTES, 0L)
                        val total = progress.getLong(ApkDownloadWorker.KEY_TOTAL_BYTES, defaultTotalBytes)

                        _updateState.value = UpdateState.Downloading(
                            progressPercent = percent,
                            bytesDownloaded = bytes,
                            totalBytes = total
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        liveData.removeObserver(this)
                        currentObserver = null
                        currentLiveData = null
                        val versionCode = workInfo.outputData.getInt(ApkDownloadWorker.KEY_VERSION_CODE, -1)
                        if (versionCode >= 0) {
                            preferences.downloadedVersionCode = versionCode
                        }
                        val apkPath = workInfo.outputData.getString(ApkDownloadWorker.KEY_APK_PATH)
                        if (apkPath != null) {
                            val apkFile = File(apkPath)
                            if (apkFile.exists()) {
                                _updateState.value = UpdateState.ReadyToInstall(
                                    apkFile,
                                    fallbackMetadata ?: restoreMetadata(versionCode)
                                )
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
                        currentLiveData = null
                        val error = workInfo.outputData.getString(ApkDownloadWorker.KEY_ERROR) ?: "Download failed"
                        _updateState.value = UpdateState.Error(error)
                    }
                    WorkInfo.State.CANCELLED -> {
                        liveData.removeObserver(this)
                        currentObserver = null
                        currentLiveData = null
                        _updateState.value = UpdateState.Idle
                    }
                    else -> {}
                }
            }
        }

        currentObserver = observer
        currentLiveData = liveData
        liveData.observeForever(observer)
    }

    private fun reattachDownloadState() {
        scope.launch {
            val infos = try {
                withContext(Dispatchers.IO) {
                    workManager.getWorkInfosForUniqueWork(WORK_NAME_DOWNLOAD_APK)
                        .get(5, TimeUnit.SECONDS)
                }
            } catch (_: Exception) {
                return@launch
            }

            val info = infos.lastOrNull() ?: return@launch
            when (info.state) {
                WorkInfo.State.RUNNING -> {
                    // Recover the expected APK size persisted at download-start so
                    // the reattached progress bar shows a determinate total instead
                    // of an indeterminate "0 MB" state.
                    observeWork(
                        workId = info.id,
                        fallbackMetadata = null,
                        defaultTotalBytes = preferences.downloadedApkSize
                    )
                }
                WorkInfo.State.SUCCEEDED -> {
                    val versionCode = info.outputData.getInt(ApkDownloadWorker.KEY_VERSION_CODE, -1)
                    if (versionCode >= 0) {
                        preferences.downloadedVersionCode = versionCode
                    }
                    val apkPath = info.outputData.getString(ApkDownloadWorker.KEY_APK_PATH)
                    val apkFile = apkPath?.let { File(it) }
                    if (apkFile != null && apkFile.exists()) {
                        _updateState.value = UpdateState.ReadyToInstall(apkFile, restoreMetadata(versionCode))
                    } else {
                        // The work claims success but the APK is unusable; surface it
                        // instead of silently staying Idle after the version was marked
                        // as downloaded.
                        _updateState.value = UpdateState.Error("Downloaded APK file not found")
                    }
                }
                else -> {}
            }
        }
    }

    private fun restoreMetadata(versionCode: Int): ReleaseMetadata {
        return ReleaseMetadata(
            versionCode = versionCode,
            versionName = preferences.downloadedVersionName ?: "",
            apkName = "",
            apkSize = 0L,
            sha256 = ""
        )
    }

    fun cancelDownload() {
        workManager.cancelUniqueWork(WORK_NAME_DOWNLOAD_APK)
        _updateState.value = UpdateState.Idle
    }

    fun installUpdate(readyState: UpdateState.ReadyToInstall): Boolean {
        return installManager.installApk(readyState.apkFile)
    }

    fun reportError(message: String) {
        _updateState.value = UpdateState.Error(message)
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
                    if (isApkStale(downloadedVersion, BuildConfig.VERSION_CODE)) {
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

        internal fun isApkStale(downloadedVersionCode: Int, currentVersionCode: Int): Boolean {
            return downloadedVersionCode >= 0 && currentVersionCode >= downloadedVersionCode
        }
    }
}
