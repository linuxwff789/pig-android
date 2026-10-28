package com.linuxwff.pigapp

import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Base64

/**
 * WebDAV settings. The password lives in the app's private SharedPreferences
 * (like the relay token): the backup itself already contains plain-text keys,
 * so this is not a second secret, but the settings dialog says so.
 */
data class WebDavConfig(
    val url: String = "",
    val user: String = "",
    val password: String = "",
    val dir: String = DEFAULT_DIR,
    val keep: Int = 7,
    /** 0 = no scheduled backup; otherwise the period in hours. */
    val everyHours: Int = 0,
) {
    val ready: Boolean get() = url.isNotBlank()

    fun dirUrl(): String {
        val base = url.trim().trimEnd('/')
        val path = dir.trim().trim('/')
        return if (path.isEmpty()) base else "$base/$path"
    }

    companion object {
        const val DEFAULT_DIR = "pig-backups"
        private const val URL_KEY = "webdav.url"
        private const val USER_KEY = "webdav.user"
        private const val PASS_KEY = "webdav.password"
        private const val DIR_KEY = "webdav.dir"
        private const val KEEP_KEY = "webdav.keep"
        private const val EVERY_KEY = "webdav.everyHours"

        fun from(prefs: SharedPreferences): WebDavConfig = WebDavConfig(
            url = prefs.getString(URL_KEY, "").orEmpty(),
            user = prefs.getString(USER_KEY, "").orEmpty(),
            password = prefs.getString(PASS_KEY, "").orEmpty(),
            dir = prefs.getString(DIR_KEY, DEFAULT_DIR).orEmpty().ifBlank { DEFAULT_DIR },
            keep = prefs.getInt(KEEP_KEY, 7).coerceIn(1, 60),
            everyHours = prefs.getInt(EVERY_KEY, 0).coerceIn(0, 24 * 14),
        )

        fun save(prefs: SharedPreferences, config: WebDavConfig) {
            prefs.edit()
                .putString(URL_KEY, config.url.trim())
                .putString(USER_KEY, config.user)
                .putString(PASS_KEY, config.password)
                .putString(DIR_KEY, config.dir.trim().trim('/'))
                .putInt(KEEP_KEY, config.keep.coerceIn(1, 60))
                .putInt(EVERY_KEY, config.everyHours.coerceIn(0, 24 * 14))
                .apply()
        }
    }
}

/** One archive on the server, newest first. */
data class WebDavBackup(
    val name: String,
    val size: Long,
    val modified: String,
)

/**
 * The tiny slice of WebDAV a backup needs: PROPFIND to list, PUT to upload, GET
 * to restore, DELETE for retention, MKCOL to create the folder. No library, so
 * the app keeps its dependency list; Basic auth only, which is what Nextcloud,
 * Alist, Synology and rclone serve.
 */
object WebDavClient {
    const val PREFIX = "pig-backup-"
    const val SUFFIX = ".tar.gz"

    private const val TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 5
    private const val PROPFIND_BODY =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
            "<d:displayname/><d:getcontentlength/><d:getlastmodified/>" +
            "</d:prop></d:propfind>"

    /** Throws with a message the settings dialog can show as-is. */
    fun test(config: WebDavConfig) {
        require(config.ready) { "先填写服务器地址" }
        val dir = config.dirUrl()
        val response = request(config, "PROPFIND", dir, PROPFIND_BODY.toByteArray(), depth = "0")
        when {
            response.code in 200..299 -> Unit
            response.code == 404 -> ensureDir(config)
            response.code == 401 || response.code == 403 ->
                error("认证失败（${response.code}），检查用户名或密码")
            else -> error(errorText(response))
        }
    }

    /** Creates every missing level of [WebDavConfig.dir]; 405 means "exists". */
    fun ensureDir(config: WebDavConfig) {
        val base = config.url.trim().trimEnd('/')
        val path = config.dir.trim().trim('/')
        if (path.isEmpty()) return
        var current = base
        for (segment in path.split('/')) {
            if (segment.isBlank()) continue
            current = "$current/$segment"
            val created = request(config, "MKCOL", current)
            if (created.code !in 200..299 && created.code != 405) {
                error("创建目录失败：${errorText(created)}")
            }
        }
    }

    fun upload(config: WebDavConfig, name: String, file: File) {
        ensureDir(config)
        val response = request(
            config,
            "PUT",
            "${config.dirUrl()}/$name",
            file = file,
            contentType = "application/gzip",
        )
        if (response.code !in 200..299) error("上传失败：${errorText(response)}")
    }

    fun list(config: WebDavConfig): List<WebDavBackup> {
        val response = request(config, "PROPFIND", config.dirUrl(), PROPFIND_BODY.toByteArray(), depth = "1")
        if (response.code == 404) return emptyList()
        if (response.code !in 200..299) error("列目录失败：${errorText(response)}")
        return parseList(response.body ?: "")
    }

    fun download(config: WebDavConfig, name: String, dest: File) {
        val response = request(config, "GET", "${config.dirUrl()}/${encodeName(name)}")
        if (response.code !in 200..299) error("下载失败：${errorText(response)}")
        val stream = response.stream ?: error("下载失败：没有响应内容")
        stream.use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        }
    }

    /**
     * Keep the newest [WebDavConfig.keep] archives; older ones are deleted so a
     * daily schedule does not fill the server.
     */
    fun prune(config: WebDavConfig, keep: Int): Int {
        val archives = list(config)
        var removed = 0
        for (old in archives.drop(keep.coerceAtLeast(1))) {
            val response = request(config, "DELETE", "${config.dirUrl()}/${encodeName(old.name)}")
            if (response.code in 200..299 || response.code == 404) removed++
        }
        return removed
    }

    private class Response(
        val code: Int,
        val text: String,
        val stream: java.io.InputStream? = null,
        val body: String? = null,
    )

    private fun errorText(response: Response): String {
        val detail = response.text.take(200).replace(Regex("\\s+"), " ").trim()
        return "HTTP ${response.code}" + if (detail.isNotBlank()) ": $detail" else ""
    }

    private fun encodeName(name: String): String =
        name.replace(" ", "%20").replace("#", "%23").replace("?", "%3F")

    private fun request(
        config: WebDavConfig,
        method: String,
        url: String,
        body: ByteArray? = null,
        depth: String? = null,
        file: File? = null,
        contentType: String? = null,
    ): Response {
        var target = url
        var redirects = 0
        while (true) {
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                if (config.user.isNotBlank() || config.password.isNotBlank()) {
                    val raw = "${config.user}:${config.password}".toByteArray()
                    setRequestProperty(
                        "Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(raw),
                    )
                }
                depth?.let { setRequestProperty("Depth", it) }
                contentType?.let { setRequestProperty("Content-Type", it) }
                when {
                    file != null -> {
                        setRequestProperty("Content-Length", file.length().toString())
                        setFixedLengthStreamingMode(file.length())
                        doOutput = true
                    }
                    body != null -> {
                        setRequestProperty("Content-Length", body.size.toString())
                        doOutput = true
                    }
                }
            }
            try {
                if (file != null) {
                    connection.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
                } else if (body != null) {
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                if (code in setOf(301, 302, 303, 307, 308) && redirects < MAX_REDIRECTS) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) error("重定向缺少 Location")
                    target = if (location.startsWith("http")) {
                        location
                    } else {
                        URL(URL(target), location).toString()
                    }
                    redirects++
                    continue
                }
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                var text = ""
                var bodyText: String? = null
                if (method == "PROPFIND" && stream != null) {
                    bodyText = stream.readBytes().toString(Charsets.UTF_8)
                } else if (stream != null && method != "GET") {
                    text = runCatching { stream.readBytes().toString(Charsets.UTF_8) }.getOrDefault("")
                    stream.close()
                } else if (stream != null) {
                    // GET: the caller copies the body (backups are large).
                    return Response(code, "", stream)
                }
                connection.disconnect()
                return Response(code, text, body = bodyText)
            } catch (error: IOException) {
                connection.disconnect()
                throw IllegalStateException("连接失败：${error.message ?: error.toString()}")
            }
        }
    }

    private fun parseList(xml: String): List<WebDavBackup> {
        val out = mutableListOf<WebDavBackup>()
        val blocks = Regex(
            "<(?:[A-Za-z0-9]+:)?response\\b[^>]*>(.*?)</(?:[A-Za-z0-9]+:)?response>",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(xml).map { it.groupValues[1] }
        for (block in blocks) {
            val href = Regex("<(?:[A-Za-z0-9]+:)?href[^>]*>([^<]+)<")
                .find(block)?.groupValues?.get(1) ?: continue
            val rawName = href.trim().trimEnd('/').substringAfterLast('/')
            val name = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) continue
            val size = Regex("<(?:[A-Za-z0-9]+:)?getcontentlength[^>]*>([0-9]+)<")
                .find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val modified = Regex("<(?:[A-Za-z0-9]+:)?getlastmodified[^>]*>([^<]+)<")
                .find(block)?.groupValues?.get(1) ?: ""
            out += WebDavBackup(name, size, modified)
        }
        return out.distinctBy { it.name }.sortedByDescending { it.name }
    }
}
