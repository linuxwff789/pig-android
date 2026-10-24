package com.linuxwff.pigapp.ui

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Images referenced from Markdown (`![alt](url)`).
 *
 * The renderer's `ImageTransformer` is synchronous, so the first pass returns
 * nothing, the bytes are fetched on a background thread, and the Compose
 * snapshot draws the picture when it lands. Only `http(s)`, `data:` and `file:`
 * links are allowed: the app has no access to Termux paths, and `content:` URIs
 * would need a grant the agent cannot give.
 */
data class RemoteImage(
    val bitmap: ImageBitmap,
    /** Media type used to pick the encoder when the picture is saved. */
    val mimeType: String,
)

object RemoteImages {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_DIM = 1600
    private const val CACHE_ENTRIES = 12

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val images = mutableStateMapOf<String, RemoteImage>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    /** Snapshot-state read: calling this from a composable redraws on arrival. */
    fun get(url: String): RemoteImage? = images[url]

    /** Start (or skip) a background fetch; call from a `LaunchedEffect`. */
    fun request(url: String) {
        if (url.isBlank()) return
        if (images.containsKey(url) || failed.contains(url)) return
        if (loading.add(url)) {
            scope.launch { load(url) }
        }
    }

    private fun load(url: String) {
        try {
            val (bytes, mimeType) = fetch(url) ?: run {
                failed.add(url)
                return
            }
            val bitmap = decode(bytes)
            if (bitmap == null) {
                failed.add(url)
            } else {
                trim()
                images[url] = RemoteImage(bitmap, mimeType)
            }
        } catch (e: Exception) {
            failed.add(url)
            Log.w("PigImages", "markdown image $url: ${e.message}")
        } finally {
            loading.remove(url)
        }
    }

    private fun trim() {
        while (images.size >= CACHE_ENTRIES) {
            val oldest = images.keys.firstOrNull() ?: return
            images.remove(oldest)
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIM) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        return bitmap.asImageBitmap()
    }

    private fun fetch(url: String): Pair<ByteArray, String>? = when {
        url.startsWith("data:", true) -> decodeDataUri(url)?.let { it to dataUriMime(url) }
        url.startsWith("http://", true) || url.startsWith("https://", true) -> fetchHttp(url)
        url.startsWith("file:", true) -> runCatching {
            val file = File(URI(url))
            if (file.isFile && file.length() <= MAX_BYTES) {
                file.readBytes() to guessMime(url)
            } else {
                null
            }
        }.getOrNull()
        else -> null
    }

    private fun dataUriMime(uri: String): String {
        val comma = uri.indexOf(',')
        if (comma <= 5) return "image/png"
        return uri.substring(5, comma).substringBefore(';').ifBlank { "image/png" }
    }

    private fun guessMime(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".bmp") -> "image/bmp"
            else -> "image/png"
        }
    }

    private fun decodeDataUri(uri: String): ByteArray? {
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val meta = uri.substring(5, comma)
        if (!meta.startsWith("image/", true)) return null
        if (!meta.contains(";base64", true)) return null
        val payload = uri.substring(comma + 1)
        if (payload.length > MAX_BYTES / 3 * 4 + 16) return null
        return runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
    }

    private fun fetchHttp(url: String): Pair<ByteArray, String>? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "pig-android")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val contentType = connection.contentType.orEmpty()
            if (!contentType.startsWith("image/")) return null
            val mimeType = contentType.substringBefore(';').ifBlank { guessMime(url) }
            connection.inputStream.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_BYTES) return null
                    out.write(buffer, 0, read)
                }
                return out.toByteArray() to mimeType
            }
        } finally {
            connection.disconnect()
        }
    }
}
