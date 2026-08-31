package com.groq.voicetyper.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

class PackageInstallManager(private val context: Context) {

    companion object {
        // Must remain byte-identical to release.yml Verify step + RELEASE_PIPELINE.md
        private const val EXPECTED_CERT_SHA256 =
            "8955bb6e81047ef452ac68763c47d16916b150a90a743a51ee92ea36b383ca3e"
    }

    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun createInstallPermissionIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun installApk(apkFile: File): Boolean {
        if (!canInstallPackages()) {
            return false
        }
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return false
        }

        // P0-A3: verify APK signing certificate matches frozen production fingerprint
        // before launching installer. Fail closed if mismatch — never launch untrusted APK.
        try {
            val pm = context.packageManager
            val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
            }
            if (archiveInfo == null) {
                Log.w("PackageInstallManager", "Could not parse APK package info, aborting install")
                return false
            }
            val certBytesList: List<ByteArray> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archiveInfo.signingInfo?.apkContentsSigners?.map { it.toByteArray() } ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                archiveInfo.signatures?.map { it.toByteArray() } ?: emptyList()
            }
            if (certBytesList.isEmpty()) {
                Log.w("PackageInstallManager", "No signing certificates found, aborting install")
                return false
            }
            val expected = EXPECTED_CERT_SHA256.lowercase()
            val matches = certBytesList.any { certBytes ->
                val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
                    .joinToString("") { "%02x".format(it) }.lowercase()
                digest == expected
            }
            if (!matches) {
                Log.w(
                    "PackageInstallManager",
                    "APK certificate does not match expected production fingerprint $expected, aborting install"
                )
                return false
            }
        } catch (e: Exception) {
            Log.w("PackageInstallManager", "Certificate verification failed, aborting install", e)
            return false
        }

        try {
            val authority = "${context.packageName}.updateprovider"
            val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            return true
        } catch (_: Exception) {
            return false
        }
    }
}
