package com.linuxwff.pigapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.linuxwff.pigapp.ChatImage
import com.linuxwff.pigapp.PendingImage

/** One picture opened in the full-screen viewer. */
data class ViewerImage(
    val bitmap: ImageBitmap,
    /** Wire media type; used for the saved file's extension and encoder. */
    val mimeType: String,
)

/**
 * The app has one full-screen image viewer. Both transcript images (decoded
 * from message blocks) and Markdown images (fetched by [RemoteImages]) open it
 * by calling [show]; [ImageViewerHost] renders it once at the app root, so a
 * Dialog never has to be nested inside a message row.
 */
object ImageViewer {
    var current by mutableStateOf<ViewerImage?>(null)
        private set

    fun show(bitmap: ImageBitmap, mimeType: String = "image/png") {
        current = ViewerImage(bitmap, mimeType.ifBlank { "image/png" })
    }

    fun close() {
        current = null
    }
}

/** Inline transcript images: user attachments, tool output and assistant blocks. */
@Composable
fun ChatImageStrip(
    images: List<ChatImage>,
    omitted: Int,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 220.dp,
    cornerRadius: Int = 12,
) {
    if (images.isEmpty() && omitted <= 0) return
    Column(modifier) {
        images.forEach { image ->
            Image(
                bitmap = image.bitmap,
                contentDescription = packText("a11y.image", "图片"),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .padding(vertical = 3.dp)
                    .heightIn(max = maxHeight)
                    .clip(RoundedCornerShape(cornerRadius.dp))
                    .clickable { ImageViewer.show(image.bitmap, image.mimeType) },
            )
        }
        if (omitted > 0) {
            Text(
                packText("chat.imagesOmitted", "{n} 张图片未显示")
                    .replace("{n}", omitted.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Composer thumbnails: 56 dp tiles with a remove badge. */
@Composable
fun AttachmentStrip(
    images: List<PendingImage>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        images.forEach { image ->
            Box(Modifier.size(56.dp)) {
                Image(
                    bitmap = image.bitmap,
                    contentDescription = packText("a11y.image", "图片"),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.62f))
                        .clickable { onRemove(image.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = packText("a11y.removeImage", "移除图片"),
                        modifier = Modifier.size(12.dp),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * Full-screen viewer: pinch to zoom, drag to pan, double-tap to reset, and a
 * save button that hands the bitmap to the ViewModel (MediaStore on Q+).
 */
@Composable
fun ImageViewerHost(onSave: (ViewerImage) -> Unit) {
    val image = ImageViewer.current ?: return
    var scale by remember(image) { mutableStateOf(1f) }
    var offset by remember(image) { mutableStateOf(Offset.Zero) }
    Dialog(
        onDismissRequest = ImageViewer::close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .pointerInput(image) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                }
                .pointerInput(image) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image.bitmap,
                contentDescription = packText("a11y.image", "图片"),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(6.dp),
            ) {
                Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.45f)) {
                    IconButton(onClick = { onSave(image) }) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = packText("a11y.saveImage", "保存图片"),
                            tint = Color.White,
                        )
                    }
                }
                Spacer(Modifier.size(6.dp))
                Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.45f)) {
                    IconButton(onClick = ImageViewer::close) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = packText("a11y.close", "关闭"),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}
