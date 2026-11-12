package com.id.doom

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/**
 * Manages GitHub Releases OTA updates for DOOM.
 * Checks for newer releases, downloads APKs with progress reporting,
 * and launches the Android system package installer via FileProvider.
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "DoomUpdateManager"
        private const val GITHUB_REPO = "kmitchel/DoomPixel10XL"
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
        private const val PREFS_NAME = "doom_ota_prefs"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
        private const val CHECK_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes between automated startup checks

        @Volatile
        var isUpdateAvailable: Boolean = false
            private set

        @Volatile
        var latestUpdate: UpdateInfo? = null
            private set

        var onUpdateAvailabilityChanged: ((Boolean) -> Unit)? = null

        /**
         * Compares semantic version strings (e.g. "1.3.17" vs "1.3.16" or "v1.3.17").
         * Returns true if [remoteVersion] is strictly newer than [currentVersion].
         */
        fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
            val rParts = remoteVersion.trim().removePrefix("v").split('.').mapNotNull { it.toIntOrNull() }
            val cParts = currentVersion.trim().removePrefix("v").split('.').mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(rParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
        }
    }

    data class UpdateInfo(
        val tagName: String,
        val versionName: String,
        val title: String,
        val releaseNotes: String,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
        val htmlUrl: String
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var pendingInstallFile: File? = null

    /**
     * Checks GitHub for the latest release.
     * @param manual If true, ignores throttling cache and forces a network fetch.
     * @param callback Result containing UpdateInfo if a newer version is available, null if up to date, or exception.
     */
    fun checkForUpdates(manual: Boolean, callback: (Result<UpdateInfo?>) -> Unit) {
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_TIME, 0L)

        if (!manual && (now - lastCheck) < CHECK_INTERVAL_MS && latestUpdate != null) {
            // Use cached result if recent
            val cached = if (isUpdateAvailable) latestUpdate else null
            mainHandler.post { callback(Result.success(cached)) }
            return
        }

        executor.execute {
            try {
                val updateInfo = fetchLatestReleaseSync()
                val currentVersion = BuildConfig.VERSION_NAME

                prefs.edit().putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()).apply()

                val hasNewer = updateInfo != null && isNewerVersion(updateInfo.versionName, currentVersion)
                isUpdateAvailable = hasNewer
                latestUpdate = if (hasNewer) updateInfo else null

                mainHandler.post {
                    onUpdateAvailabilityChanged?.invoke(hasNewer)
                    callback(Result.success(if (hasNewer) updateInfo else null))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}", e)
                mainHandler.post { callback(Result.failure(e)) }
            }
        }
    }

    private fun fetchLatestReleaseSync(): UpdateInfo? {
        val conn = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "DOOM-Android-OTA")
        }

        val code = conn.responseCode
        if (code != 200) {
            val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw IOException("GitHub API returned HTTP $code: $errBody")
        }

        val responseJson = conn.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(responseJson)

        val tagName = root.optString("tag_name", "")
        val title = root.optString("name", tagName)
        val body = root.optString("body", "")
        val htmlUrl = root.optString("html_url", "")
        val versionName = tagName.removePrefix("v").trim()

        val assets = root.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        var apkName: String? = null
        var apkSize: Long = 0L

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkName = name
                apkUrl = asset.optString("browser_download_url", "")
                apkSize = asset.optLong("size", 0L)
                break
            }
        }

        if (apkUrl.isNullOrBlank() || apkName.isNullOrBlank()) {
            return null
        }

        return UpdateInfo(
            tagName = tagName,
            versionName = versionName,
            title = title,
            releaseNotes = body,
            apkUrl = apkUrl,
            apkName = apkName,
            apkSize = apkSize,
            htmlUrl = htmlUrl
        )
    }

    /**
     * Downloads the APK file to the app's cache directory, following redirects.
     */
    fun downloadApk(
        updateInfo: UpdateInfo,
        isCanceled: () -> Boolean,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File {
        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()

        val safeApkName = File(updateInfo.apkName).name.takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: throw IOException("Invalid update APK filename: ${updateInfo.apkName}")
        val destFile = File(updatesDir, safeApkName)
        if (destFile.exists() && destFile.length() == updateInfo.apkSize && updateInfo.apkSize > 0) {
            Log.i(TAG, "Cached APK already exists and matches expected size (${destFile.length()} bytes)")
            onProgress(destFile.length(), destFile.length())
            return destFile
        }

        val tempFile = File(updatesDir, "$safeApkName.tmp")
        if (tempFile.exists()) tempFile.delete()

        val conn = openConnectionWithRedirects(updateInfo.apkUrl)
        val contentLength = conn.contentLengthLong.takeIf { it > 0 } ?: updateInfo.apkSize
        val buffer = ByteArray(16384)
        var totalRead = 0L

        try {
            FileOutputStream(tempFile).use { out ->
                conn.inputStream.use { input ->
                    var bytes: Int
                    while (input.read(buffer).also { bytes = it } != -1) {
                        if (isCanceled()) {
                            tempFile.delete()
                            throw CancellationException("Download canceled by user")
                        }
                        out.write(buffer, 0, bytes)
                        totalRead += bytes
                        onProgress(totalRead, contentLength)
                    }
                    out.flush()
                }
            }

            if (destFile.exists()) destFile.delete()
            if (!tempFile.renameTo(destFile)) {
                throw IOException("Failed to rename temporary download file to $destFile")
            }
            return destFile
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnectionWithRedirects(initialUrl: String, maxRedirects: Int = 5): HttpURLConnection {
        var url = initialUrl
        for (i in 0 until maxRedirects) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DOOM-Android-OTA")
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            if (code in 301..308) {
                val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without Location header")
                val resolvedUrl = URL(URL(url), location).toString()
                conn.disconnect()
                url = resolvedUrl
                continue
            }
            if (code in 200..299) {
                return conn
            }
            conn.disconnect()
            throw IOException("HTTP error $code from $url")
        }
        throw IOException("Too many redirects ($maxRedirects) when connecting to $initialUrl")
    }

    /**
     * Checks unknown apps permission and triggers package installer.
     */
    fun installApk(activity: Activity, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                pendingInstallFile = apkFile
                AlertDialog.Builder(activity)
                    .setTitle("Install Permission Required")
                    .setMessage("Android requires permission to install app updates from unknown sources.\n\nPlease tap 'Open Settings' and enable 'Allow from this source', then return to DOOM to complete the update.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                        activity.startActivity(intent)
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        pendingInstallFile = null
                    }
                    .show()
                return
            }
        }
        launchInstallIntent(activity, apkFile)
    }

    fun launchInstallIntent(context: Context, apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }
}
