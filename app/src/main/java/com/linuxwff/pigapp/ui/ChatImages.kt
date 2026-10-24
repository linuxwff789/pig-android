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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.linuxwff.pigapp.ChatImage

/**
 * Inline transcript images: user attachments, tool output (`read` on an image,
 * screenshots, image models) and assistant image blocks. Tap one for a
 * full-screen, pannable/zoomable view.
 */
@Composable
fun ChatImageStrip(
    images: List<ChatImage>,
    omitted: Int,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 220.dp,
    cornerRadius: Int = 12,
) {
    var viewer by remember { mutableStateOf<ChatImage?>(null) }
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
                    .clickable { viewer = image },
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
    viewer?.let { image -> ImageViewer(image) { viewer = null } }
}

/** Composer thumbnails: 56 dp tiles with a remove badge. */
@Composable
fun AttachmentStrip(
    images: List<com.linuxwff.pigapp.PendingImage>,
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

@Composable
private fun ImageViewer(image: ChatImage, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
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
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding(),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = packText("a11y.close", "关闭"),
                    tint = Color.White,
                )
            }
        }
    }
}
