package com.linuxwff.pigapp

import android.content.SharedPreferences
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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
    /** Daily runs start at this minute of the day (default 03:00). */
    val dailyMinutes: Int = DEFAULT_DAILY_MINUTES,
    /**
     * Accept a self-signed / mismatched certificate for this server. Off by
     * default; the dialog spells out what it means.
     */
    val insecureTls: Boolean = false,
) {
    val ready: Boolean get() = url.isNotBlank()

    fun dirUrl(): String {
        val base = url.trim().trimEnd('/')
        val path = dir.trim().trim('/')
        return if (path.isEmpty()) base else "$base/$path"
    }

    companion object {
        const val DEFAULT_DIR = "pig-backups"
        /** 03:00, the default time for a daily backup. */
        const val DEFAULT_DAILY_MINUTES = 3 * 60
        private const val URL_KEY = "webdav.url"
        private const val USER_KEY = "webdav.user"
        private const val PASS_KEY = "webdav.password"
        private const val DIR_KEY = "webdav.dir"
        private const val KEEP_KEY = "webdav.keep"
        private const val EVERY_KEY = "webdav.everyHours"
        private const val DAILY_KEY = "webdav.dailyMinutes"
        private const val INSECURE_KEY = "webdav.insecureTls"

        fun from(prefs: SharedPreferences): WebDavConfig = WebDavConfig(
            url = prefs.getString(URL_KEY, "").orEmpty(),
            user = prefs.getString(USER_KEY, "").orEmpty(),
            password = prefs.getString(PASS_KEY, "").orEmpty(),
            dir = prefs.getString(DIR_KEY, DEFAULT_DIR).orEmpty().ifBlank { DEFAULT_DIR },
            keep = prefs.getInt(KEEP_KEY, 7).coerceIn(1, 60),
            // Only "off" and "daily" exist now; a stored 6/12/72/168 h value
            // from an older version becomes daily at the chosen hour.
            everyHours = if (prefs.getInt(EVERY_KEY, 0) <= 0) 0 else 24,
            dailyMinutes = prefs.getInt(DAILY_KEY, DEFAULT_DAILY_MINUTES).coerceIn(0, 24 * 60 - 1),
            insecureTls = prefs.getBoolean(INSECURE_KEY, false),
        )

        fun save(prefs: SharedPreferences, config: WebDavConfig) {
            prefs.edit()
                .putString(URL_KEY, config.url.trim())
                .putString(USER_KEY, config.user)
                .putString(PASS_KEY, config.password)
                .putString(DIR_KEY, config.dir.trim().trim('/'))
                .putInt(KEEP_KEY, config.keep.coerceIn(1, 60))
                .putInt(EVERY_KEY, if (config.everyHours <= 0) 0 else 24)
                .putInt(DAILY_KEY, config.dailyMinutes.coerceIn(0, 24 * 60 - 1))
                .putBoolean(INSECURE_KEY, config.insecureTls)
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

    private const val MAX_REDIRECTS = 5
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
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

    /** Deletes one archive; a missing file counts as deleted. */
    fun delete(config: WebDavConfig, name: String) {
        val response = request(config, "DELETE", "${config.dirUrl()}/${encodeName(name)}")
        if (response.code !in 200..299 && response.code != 404) {
            error("删除失败：${errorText(response)}")
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
        val head = "HTTP ${response.code}" + if (detail.isNotBlank()) ": $detail" else ""
        return head + pathHint(response.code)
    }

    /**
     * 404/405 on WebDAV usually means the URL stops short of the DAV endpoint
     * (AList serves it under `/dav`, Synology under `/webdav`).
     */
    private fun pathHint(code: Int): String =
        if (code == 404 || code == 405) {
            "（地址可能要写到 WebDAV 路径，例如 AList 用 /dav、群晖用 /webdav）"
        } else {
            ""
        }

    private fun encodeName(name: String): String =
        name.replace(" ", "%20").replace("#", "%23").replace("?", "%3F")

    /**
     * One HTTP round trip for any method. `HttpURLConnection` refuses anything
     * outside its whitelist (`ProtocolException: ... but was MKCOL`), and
     * WebDAV needs PROPFIND and MKCOL, so this goes through OkHttp.
     */
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
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            error("服务器地址要以 http:// 或 https:// 开头：$target")
        }
        while (true) {
            val declared = contentType
                ?: if (method == "PROPFIND") "application/xml; charset=utf-8" else null
            val mediaType = declared?.toMediaTypeOrNull()
            val payload = when {
                file != null -> file.asRequestBody(mediaType)
                body != null -> body.toRequestBody(mediaType)
                else -> null
            }
            val builder = Request.Builder().url(target).method(method, payload)
            if (config.user.isNotBlank() || config.password.isNotBlank()) {
                val raw = "${config.user}:${config.password}".toByteArray()
                builder.header(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(raw),
                )
            }
            depth?.let { builder.header("Depth", it) }
            val response = try {
                httpClient(config).newCall(builder.build()).execute()
            } catch (error: IOException) {
                throw IllegalStateException(friendlyError(error))
            }
            if (response.code in REDIRECT_CODES && redirects < MAX_REDIRECTS) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) error("重定向缺少 Location")
                val current = URL(target)
                val next = if (location.startsWith("http")) URL(location) else URL(current, location)
                // The Authorization header below is rebuilt on every loop, so a
                // redirect used to hand the NAS password to whatever host the
                // server named (and accepted https -> http downgrades).
                if (!next.host.equals(current.host, ignoreCase = true)) {
                    error("拒绝跨主机重定向：${current.host} -> ${next.host}")
                }
                if (current.protocol.equals("https", true) &&
                    !next.protocol.equals("https", true)
                ) {
                    error("拒绝把 HTTPS 重定向到明文 HTTP")
                }
                target = next.toString()
                redirects++
                continue
            }
            // GET keeps the body open for the caller (backups are large); the
            // closing happens when that stream is closed.
            if (method == "GET" && response.code in 200..299) {
                return Response(response.code, "", stream = response.body?.byteStream())
            }
            response.use { done ->
                val text = done.body?.string().orEmpty()
                return Response(
                    done.code,
                    text,
                    body = if (method == "PROPFIND") text else null,
                )
            }
        }
    }

    /** Clients are cheap but the pool is not: one per TLS mode is enough. */
    private val clients = java.util.concurrent.ConcurrentHashMap<Boolean, OkHttpClient>()

    private fun httpClient(config: WebDavConfig): OkHttpClient =
        clients.getOrPut(config.insecureTls) {
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
            if (config.insecureTls) {
                val trustAll = trustAllManager()
                val context = SSLContext.getInstance("TLS")
                context.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
                builder.sslSocketFactory(context.socketFactory, trustAll)
                builder.hostnameVerifier { _, _ -> true }
            }
            builder.build()
        }

    /**
     * Opt-in trust-all, for a NAS with a self-signed certificate. Only used by
     * this client, never globally.
     */
    private fun trustAllManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Turn the usual Android/Java failures into something a user can act on. */
    private fun friendlyError(error: IOException): String {
        val text = error.message ?: error.javaClass.simpleName
        return when {
            text.contains("Cleartext", ignoreCase = true) ->
                "系统拦截了明文 HTTP（$text）：改用 https，或使用已允许明文的新版本"
            error is javax.net.ssl.SSLException ->
                "TLS/证书错误：$text（自签名证书请勾选「忽略证书错误」）"
            error is java.net.UnknownHostException -> "域名解析失败：$text"
            error is java.net.SocketTimeoutException -> "连接超时：$text"
            error is java.net.ConnectException -> "连不上服务器：$text"
            else -> "连接失败：$text"
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
