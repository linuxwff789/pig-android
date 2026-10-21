package com.linuxwff.pigapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linuxwff.pigapp.AppViewModel
import com.linuxwff.pigapp.AppWidget
import com.linuxwff.pigapp.AppWindow
import com.linuxwff.pigapp.UiNode

@Composable
fun WindowLayer(windows: List<AppWindow>, vm: AppViewModel) {
    val overlays = windows.filter { it.placement == "overlay" }
    if (overlays.isEmpty()) return
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.toFloat()
    val screenHeight = configuration.screenHeightDp.toFloat()
    Box(Modifier.fillMaxSize()) {
        overlays.forEach { window ->
            key(window.id) {
                AppWindowView(window, vm, screenWidth, screenHeight)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetWindows(windows: List<AppWindow>, vm: AppViewModel) {
    val sheet = windows.lastOrNull { it.placement == "sheet" } ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { vm.closeWindow(sheet.id) },
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        ) {
            Text(sheet.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                UiNodeView(sheet.body, sheet.id, vm)
            }
        }
    }
}

@Composable
fun InlineWindows(windows: List<AppWindow>, placement: String, vm: AppViewModel) {
    windows.filter { it.placement == placement }.forEach { window ->
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        window.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (window.dismissible) {
                        IconButton(
                            onClick = { vm.closeWindow(window.id) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "close ${window.title}",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    UiNodeView(window.body, window.id, vm)
                }
            }
        }
    }
}

@Composable
fun WidgetPane(widgets: List<AppWidget>, placement: String, vm: AppViewModel) {
    val items = widgets.filter { it.placement == placement }
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        items.forEach { widget ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                Text(
                    widget.lines.joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .padding(8.dp)
                        .heightIn(max = 140.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun AppWindowView(window: AppWindow, vm: AppViewModel, screenWidth: Float, screenHeight: Float) {
    val density = LocalDensity.current
    val position = vm.positionOf(window.id)
    val maxX = (screenWidth - window.widthDp - 8f).coerceAtLeast(0f)
    val maxY = (screenHeight - 120f).coerceAtLeast(0f)

    Surface(
        modifier = Modifier
            .offset(x = position.x.dp, y = position.y.dp)
            .width(window.widthDp.dp)
            .then(if (window.heightDp > 0) Modifier.height(window.heightDp.dp) else Modifier),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.focusWindow(window.id) }
                    .pointerInput(window.id, maxX, maxY) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            vm.moveWindow(
                                window.id,
                                dragAmount.x / density.density,
                                dragAmount.y / density.density,
                                maxX,
                                maxY,
                            )
                        }
                    }
                    .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    window.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (window.dismissible) {
                    IconButton(
                        onClick = { vm.closeWindow(window.id) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "close ${window.title}",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            if (window.heightDp > 0) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    UiNodeView(window.body, window.id, vm)
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                ) {
                    UiNodeView(window.body, window.id, vm)
                }
            }
        }
    }
}

@Composable
private fun UiNodeView(node: UiNode, windowId: String, vm: AppViewModel) {
    if (!node.visible) return
    val pad = Modifier.padding(node.padding.dp)

    when (node.type) {
        "column" -> Column(
            pad.then(if (node.scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            verticalArrangement = Arrangement.spacedBy(node.spacing.dp),
            horizontalAlignment = when (node.align) {
                "center" -> Alignment.CenterHorizontally
                "end" -> Alignment.End
                else -> Alignment.Start
            },
        ) {
            node.children.forEach { child ->
                if (child.weight > 0f) {
                    Box(Modifier.weight(child.weight)) { UiNodeView(child, windowId, vm) }
                } else {
                    UiNodeView(child, windowId, vm)
                }
            }
        }

        "row" -> Row(
            pad,
            horizontalArrangement = Arrangement.spacedBy(node.spacing.dp),
            verticalAlignment = when (node.align) {
                "center" -> Alignment.CenterVertically
                "bottom", "end" -> Alignment.Bottom
                else -> Alignment.Top
            },
        ) {
            node.children.forEach { child ->
                if (child.weight > 0f) {
                    Box(Modifier.weight(child.weight)) { UiNodeView(child, windowId, vm) }
                } else {
                    UiNodeView(child, windowId, vm)
                }
            }
        }

        "text" -> Text(
            node.text,
            modifier = pad,
            style = textStyle(node.style),
            color = textColor(node.style),
            textAlign = when (node.align) {
                "center" -> TextAlign.Center
                "end" -> TextAlign.End
                else -> TextAlign.Start
            },
        )

        "code" -> Box(
            pad
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
        ) {
            Text(
                node.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = node.maxLines,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }

        "progress" -> if (node.indeterminate) {
            LinearProgressIndicator(modifier = pad.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { node.value }, modifier = pad.fillMaxWidth())
        }

        "button" -> {
            val onClick = { vm.handleWindowAction(windowId, node.action) }
            when (node.buttonStyle) {
                "outline" -> OutlinedButton(
                    onClick = onClick,
                    enabled = node.enabled,
                    modifier = pad,
                ) { Text(node.label) }

                "text" -> TextButton(
                    onClick = onClick,
                    enabled = node.enabled,
                    modifier = pad,
                ) { Text(node.label) }

                "danger" -> Button(
                    onClick = onClick,
                    enabled = node.enabled,
                    modifier = pad,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(node.label) }

                else -> Button(
                    onClick = onClick,
                    enabled = node.enabled,
                    modifier = pad,
                ) { Text(node.label) }
            }
        }

        "textfield" -> OutlinedTextField(
            value = vm.formValue(windowId, node.fieldId, node.initialValue),
            onValueChange = { vm.setFormValue(windowId, node.fieldId, it) },
            modifier = pad.fillMaxWidth(),
            placeholder = { if (node.placeholder.isNotBlank()) Text(node.placeholder) },
            maxLines = node.maxLines,
            singleLine = node.maxLines == 1,
        )

        "select" -> Column(
            pad.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val current = vm.formValue(
                windowId,
                node.fieldId,
                node.options.firstOrNull()?.value ?: node.initialValue,
            )
            node.options.forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.setFormValue(windowId, node.fieldId, option.value) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = current == option.value,
                        onClick = { vm.setFormValue(windowId, node.fieldId, option.value) },
                    )
                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        "toggle" -> Row(
            pad.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                node.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = vm.formValue(windowId, node.fieldId, node.checked.toString()) == "true",
                onCheckedChange = { vm.setFormValue(windowId, node.fieldId, it.toString()) },
            )
        }

        "list" -> Column(
            pad.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(node.spacing.dp),
        ) {
            node.children.forEachIndexed { index, child ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
                UiNodeView(child, windowId, vm)
            }
        }

        "table" -> Column(pad.fillMaxWidth()) {
            if (node.headers.isNotEmpty()) {
                Row(Modifier.fillMaxWidth()) {
                    node.headers.forEach { cell ->
                        Text(
                            cell,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
            node.rows.forEach { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    row.forEach { cell ->
                        Text(
                            cell,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        "divider" -> HorizontalDivider(
            modifier = pad.fillMaxWidth(),
            color = MaterialTheme.colorScheme.outline,
        )

        "spacer" -> Spacer(Modifier.size(node.size.dp))

        else -> Text(
            "[unsupported: ${node.type}]",
            modifier = pad,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun textStyle(style: String): TextStyle = when (style) {
    "heading" -> MaterialTheme.typography.titleMedium
    "label" -> MaterialTheme.typography.labelSmall
    "mono" -> MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    else -> MaterialTheme.typography.bodyLarge
}

@Composable
private fun textColor(style: String) = when (style) {
    "muted" -> MaterialTheme.colorScheme.onSurfaceVariant
    "danger" -> MaterialTheme.colorScheme.error
    "label" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}
