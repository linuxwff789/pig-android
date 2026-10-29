package com.linuxwff.pigapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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
 *
 * It deliberately avoids `api.github.com`: an unauthenticated API call is
 * capped at **60 requests per hour per IP**, and mobile networks share one
 * public IP between many users, so the app used to answer "GitHub 限流" while
 * nothing was wrong with the release. The atom feed and the `releases/latest`
 * redirect are ordinary pages and are not part of that quota; the API is only
 * the last resort (it is the only source of the release notes when both pages
 * are unreachable).
 */
object UpdateChecker {
    private const val REPO = "linuxwff789/pig-android"
    private const val ATOM = "https://github.com/$REPO/releases.atom"
    private const val LATEST_PAGE = "https://github.com/$REPO/releases/latest"
    // /releases/latest skips pre-releases; the list endpoint includes them.
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases?per_page=1"
    const val RELEASES_URL = "https://github.com/$REPO/releases"
    // Same reason as in RelayLauncher: releases are pre-releases, so the asset
    // of `/releases/latest` is the script of the last stable build.
    const val INSTALL_COMMAND =
        "curl -fsSL https://raw.githubusercontent.com/linuxwff789/pig-android" +
            "/main/scripts/install-termux.sh | sh"

    /** The feed rarely changes; a repeated tap must not hammer GitHub either. */
    private const val CACHE_MS = 5 * 60_000L
    @Volatile private var cacheKey: String = ""
    @Volatile private var cachedAt: Long = 0L
    @Volatile private var cached: UpdateInfo? = null

    suspend fun check(currentVersion: String): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        val hit = cached
        if (hit != null && cacheKey == currentVersion &&
            System.currentTimeMillis() - cachedAt < CACHE_MS
        ) {
            return@withContext Result.success(hit)
        }
        runCatching {
            // The atom feed lists pre-releases, so it is the only source that
            // can see a preview. The redirect points at the newest *stable*
            // release and may therefore look up to date while a preview is
            // out; it is only accepted when it really is newer, and the API
            // list (previews included) is tried before falling back to it.
            val atom = fromAtom(currentVersion)
            val redirect = if (atom == null) fromRedirect(currentVersion) else null
            val info = atom
                ?: redirect?.takeIf { it.newer }
                ?: runCatching { fromApi(currentVersion) }.getOrNull()
                ?: redirect
                ?: throw IOException("cannot resolve the newest release")
            cacheKey = currentVersion
            cachedAt = System.currentTimeMillis()
            cached = info
            info
        }
    }

    /** First `<entry>` of the releases feed: tag, URL and the notes as text. */
    private fun fromAtom(currentVersion: String): UpdateInfo? {
        val body = runCatching { get(ATOM, "application/atom+xml") }.getOrNull() ?: return null
        val entry = Regex("<entry>.*?</entry>", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.value ?: return null
        val tag = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(entry)?.groupValues?.get(1)
            ?.trim()
            ?.replace(Regex("<[^>]+>"), "")
            ?.ifBlank { null } ?: return null
        val url = Regex("href=\"([^\"]*/releases/tag/[^\"]+)\"")
            .find(entry)?.groupValues?.get(1)
            ?: RELEASES_URL
        val notes = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
            .find(entry)?.groupValues?.get(1)
            ?: ""
        return build(currentVersion, tag, url, htmlToText(notes))
    }

    /**
     * `releases/latest` answers with a redirect to `/releases/tag/vX`; following
     * it needs no API call. Notes stay empty here (the atom feed supplies them
     * when it is reachable).
     */
    private fun fromRedirect(currentVersion: String): UpdateInfo? {
        val finalUrl = runCatching { finalUrl(LATEST_PAGE) }.getOrNull() ?: return null
        val tag = Regex("/releases/tag/([^/?#]+)").find(finalUrl)?.groupValues?.get(1)
            ?: return null
        return build(currentVersion, tag, finalUrl, "")
    }

    private fun fromApi(currentVersion: String): UpdateInfo {
        val connection = open(LATEST_API, "application/vnd.github+json", currentVersion)
        try {
            val code = connection.responseCode
            if (code != 200) throw httpError(code, connection)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val release = JSONArray(body).optJSONObject(0)
                ?: throw IOException("no releases")
            val tag = release.optString("tag_name").ifBlank { "" }
            return build(
                currentVersion,
                tag,
                release.optString("html_url").ifBlank { RELEASES_URL },
                release.optString("body").take(4000),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun build(
        currentVersion: String,
        tag: String,
        url: String,
        notes: String,
    ): UpdateInfo {
        val version = normalize(tag)
        return UpdateInfo(
            tag = tag.ifBlank { "v$version" },
            version = version,
            url = url,
            notes = notes,
            newer = isNewer(version, normalize(currentVersion)),
        )
    }

    private fun open(url: String, accept: String, currentVersion: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "PigAndroid/$currentVersion")
        }

    private fun get(url: String, accept: String): String {
        val connection = open(url, accept, "update")
        try {
            val code = connection.responseCode
            if (code != 200) throw httpError(code, connection)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun finalUrl(url: String): String {
        val connection = open(url, "text/html", "update")
        try {
            connection.responseCode
            return connection.url.toString()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Say how long the limit lasts instead of a bare "403": GitHub sends
     * `retry-after` (seconds) or `x-ratelimit-reset` (epoch seconds).
     */
    private fun httpError(code: Int, connection: HttpURLConnection): Exception {
        if (code == 403 || code == 429) {
            val retryAfter = connection.getHeaderField("retry-after")?.trim()?.toLongOrNull()
            val reset = connection.getHeaderField("x-ratelimit-reset")?.trim()?.toLongOrNull()
            val seconds = retryAfter
                ?: reset?.minus(System.currentTimeMillis() / 1000)
                ?: -1
            val whenText = if (seconds > 0) {
                "约 ${(seconds + 59) / 60} 分钟后"
            } else {
                "稍后"
            }
            return IllegalStateException("GitHub 限流，${whenText}再试")
        }
        return IllegalStateException(
            when (code) {
                404 -> "没有找到发布记录"
                else -> "HTTP $code"
            },
        )
    }

    /** Atom content is escaped HTML; the dialog shows plain text. */
    private fun htmlToText(raw: String): String =
        raw.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .take(4000)

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
