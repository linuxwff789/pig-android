package com.linuxwff.pigapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One GitHub release, reduced to what the About section needs. */
data class UpdateInfo(
    val tag: String,
    val version: String,
    val url: String,
    val notes: String,
    val newer: Boolean,
)

/**
 * Checks the public release feed for a newer Pig Android. This is the app APK
 * only: the Termux side (pig / pig-relay) is updated by rerunning the install
 * script, which the About section also offers.
 */
object UpdateChecker {
    private const val REPO = "linuxwff789/pig-android"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_URL = "https://github.com/$REPO/releases/latest"
    const val INSTALL_COMMAND =
        "curl -fsSL https://github.com/$REPO/releases/latest/download/install-termux.sh | sh"

    suspend fun check(currentVersion: String): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(LATEST).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "PigAndroid/$currentVersion")
            }
            try {
                val code = connection.responseCode
                if (code != 200) {
                    throw IllegalStateException(
                        when (code) {
                            403, 429 -> "GitHub 限流，稍后再试"
                            404 -> "没有找到发布记录"
                            else -> "HTTP $code"
                        },
                    )
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val release = JSONObject(body)
                val tag = release.optString("tag_name").ifBlank { "" }
                val url = release.optString("html_url").ifBlank { RELEASES_URL }
                val version = normalize(tag)
                UpdateInfo(
                    tag = tag.ifBlank { "v$version" },
                    version = version,
                    url = url,
                    notes = release.optString("body").take(4000),
                    newer = isNewer(version, normalize(currentVersion)),
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    /** "v1.2.3-rc1" -> "1.2.3-rc1"; keeps suffixes so 1.2.3-rc1 != 1.2.3. */
    private fun normalize(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").ifBlank { "0" }

    /** Numeric compare of dot-separated versions; a suffix makes it older. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parts(latest)
        val b = parts(current)
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        // Same numbers: only treat "1.2.3" as newer than "1.2.3-rc1".
        return latest.substringBefore('-') != latest && current.substringBefore('-') == current
    }

    private fun parts(version: String): List<Int> =
        version.substringBefore('-')
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}
