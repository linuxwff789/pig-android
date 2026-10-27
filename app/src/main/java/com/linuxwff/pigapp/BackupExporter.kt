package com.linuxwff.pigapp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where a backup archive lands: `Download/Pig/` on Android 10+, like the
 * diagnostic log. Older releases cannot write to shared storage without
 * `WRITE_EXTERNAL_STORAGE`, so [create] returns null and the settings dialog
 * asks through the system file picker instead (also on "sdcard", no permission).
 *
 * The stream is opened by the caller and written chunk by chunk - a session
 * tree can be tens of megabytes, so the archive is never held in memory.
 */
object BackupExporter {
    const val MIME = "application/gzip"

    data class Target(
        val uri: Uri,
        val location: String,
        /** Called once with whether the transfer completed; clears IS_PENDING. */
        val finish: (Boolean) -> Unit,
    )

    fun suggestedName(): String =
        "pig-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".tar.gz"

    /** MediaStore target on Android 10+, null when the caller must use the picker. */
    fun create(context: Context, name: String): Target? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStore(context.applicationContext, name)
        } else {
            null
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStore(context: Context, name: String): Target? = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Pig")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Download 里创建不了文件")
        Target(uri, "Download/Pig/$name") { ok ->
            if (ok) {
                runCatching {
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null,
                        null,
                    )
                }
            } else {
                // A cancelled or failed export must not leave a 0-byte entry
                // in the user's Download folder.
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }.onFailure {
        AppLog.event("backup", "media store target failed: ${it.message}")
    }.getOrNull()
}
