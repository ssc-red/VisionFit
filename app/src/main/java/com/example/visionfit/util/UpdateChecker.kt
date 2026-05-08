package com.example.visionfit.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.example.visionfit.model.GitHubRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    private const val GITHUB_API_URL =
        "https://api.github.com/repos/ssc-red/VisionFit/releases"
    private const val DOWNLOAD_TIMEOUT_MS = 30000

    data class UpdateResult(
        val release: GitHubRelease?,
        val error: String? = null
    )

    suspend fun checkForUpdate(context: Context): UpdateResult {
        return withContext(Dispatchers.IO) {
            try {
                val currentVersion = getCurrentVersionName(context)
                val releases = fetchReleases()
                val latest = releases.firstOrNull() ?: return@withContext UpdateResult(null, "No releases found")

                if (latest.versionName.isBlank()) {
                    return@withContext UpdateResult(null, "Could not parse latest version")
                }

                if (GitHubRelease.isNewer(latest.versionName, currentVersion)) {
                    UpdateResult(latest)
                } else {
                    UpdateResult(null) // up to date
                }
            } catch (e: Exception) {
                UpdateResult(null, e.message ?: "Unknown error")
            }
        }
    }

    suspend fun downloadApk(
        context: Context,
        release: GitHubRelease,
        onProgress: (Int) -> Unit = {}
    ): File? {
        val url = release.apkDownloadUrl ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val fileName = "VisionFit-${release.versionName}.apk"
                val downloadsDir = File(context.cacheDir, "updates")
                downloadsDir.mkdirs()
                val outputFile = File(downloadsDir, fileName)
                // Clean old files
                downloadsDir.listFiles()?.forEach { it.delete() }

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = DOWNLOAD_TIMEOUT_MS
                connection.readTimeout = DOWNLOAD_TIMEOUT_MS
                connection.connect()

                val contentLength = connection.contentLength
                val inputStream = connection.inputStream
                val outputStream = FileOutputStream(outputFile)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    if (contentLength > 0) {
                        val progress = ((totalBytesRead * 100) / contentLength).toInt()
                        onProgress(progress)
                    }
                }

                outputStream.close()
                inputStream.close()
                connection.disconnect()

                outputFile
            } catch (e: Exception) {
                null
            }
        }
    }

    fun installApk(context: Context, apkFile: File): Boolean {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun getCurrentVersionName(context: Context): String {
        return try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pkgInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    private fun fetchReleases(): List<GitHubRelease> {
        val connection = URL(GITHUB_API_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        return try {
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                throw Exception("GitHub API returned $responseCode")
            }
            val json = connection.inputStream.bufferedReader().readText()
            val array = JSONArray(json)
            val releases = mutableListOf<GitHubRelease>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val draft = obj.optBoolean("draft", false)
                val prerelease = obj.optBoolean("prerelease", false)
                // Skip drafts and pre-releases
                if (draft || prerelease) continue
                val release = GitHubRelease.fromJson(obj)
                if (release != null && release.apkDownloadUrl != null) {
                    releases.add(release)
                }
            }
            releases
        } finally {
            connection.disconnect()
        }
    }
}