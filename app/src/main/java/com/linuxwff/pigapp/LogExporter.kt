package com.linuxwff.pigapp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi

/**
 * Writes the exported log where a user can actually find it: `Download/Pig/`.
 *
 * On Android 10+ MediaStore does that without any permission. Older releases
 * cannot write to shared storage without `WRITE_EXTERNAL_STORAGE`, so [direct]
 * returns null there and the caller falls back to the system file picker (which
 * also shows up on "sdcard" and needs no permission).
 */
object LogExporter {
    data class Saved(val location: String, val uri: Uri)

    fun direct(context: Context, text: String, name: String): Saved? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStore(context.applicationContext, text, name)
        } else {
            null
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStore(context: Context, text: String, name: String): Saved? = runCatching {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Pig")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Download 里创建不了文件")
        resolver.openOutputStream(uri)?.use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
        } ?: error("打不开输出流")
        resolver.update(uri, ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }, null, null)
        Saved("Download/Pig/$name", uri)
    }.onFailure {
        AppLog.event("log", "media store export failed: ${it.message}")
    }.getOrNull()
}
