package com.example.visionfit.model

import org.json.JSONObject

data class GitHubRelease(
    val tagName: String,
    val versionName: String,
    val body: String,
    val apkDownloadUrl: String?,
    val createdAt: String
) {
    companion object {
        private val TAG_REGEX = Regex("""v?(\d+\.\d+(?:\.\d+)?)""")

        fun parseVersion(tagName: String): String {
            val match = TAG_REGEX.find(tagName)
            return match?.groupValues?.getOrNull(1) ?: tagName
        }

        fun fromJson(json: JSONObject): GitHubRelease? {
            val tagName = json.optString("tag_name", "") ?: return null
            if (tagName.isBlank()) return null
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            return GitHubRelease(
                tagName = tagName,
                versionName = parseVersion(tagName),
                body = json.optString("body", ""),
                apkDownloadUrl = apkUrl,
                createdAt = json.optString("created_at", "")
            )
        }

        fun isNewer(latestVersion: String, currentVersion: String): Boolean {
            val partsA = latestVersion.split(".").mapNotNull { it.toIntOrNull() }
            val partsB = currentVersion.split(".").mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(partsA.size, partsB.size)
            for (i in 0 until maxLen) {
                val a = partsA.getOrElse(i) { 0 }
                val b = partsB.getOrElse(i) { 0 }
                if (a > b) return true
                if (a < b) return false
            }
            return false
        }
    }
}