package com.linuxwff.pigapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import com.linuxwff.pigapp.formatRpcValue
import com.linuxwff.pigapp.resolveUiPath

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
        shape = RoundedCornerShape(
            topStart = vm.ui.uiPack.theme.cornerRadius.dp,
            topEnd = vm.ui.uiPack.theme.cornerRadius.dp,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionDot(true)
                Spacer(Modifier.width(8.dp))
                Text(sheet.title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
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
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(vm.ui.uiPack.theme.cornerRadius.dp),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
            ),
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
                                contentDescription = packText("a11y.closeWindow", "关闭 {title}")
                                    .replace("{title}", window.title),
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
            // Extensions that follow the plan contract (docs/plan-widget.md)
            // get a checklist card; everyone else keeps the plain pane.
            val plan = parsePlanWidget(widget.key, widget.lines)
            if (plan != null) {
                PlanCard(
                    plan,
                    collapsed = widget.key in vm.ui.collapsedWidgets,
                    onToggle = { vm.toggleWidgetCollapsed(widget.key) },
                    onDismiss = { vm.dismissWidget(widget.key) },
                )
                return@forEach
            }
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape((vm.ui.uiPack.theme.cornerRadius / 2).coerceAtLeast(6).dp),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                Text(
                    widget.lines.joinToString("\n"),
                    fontFamily = packCodeFont(),
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
    // A narrower screen after rotation/fold must not leave the window (and its
    // only drag handle) off-screen until the next drag clamps it.
    val x = position.x.coerceIn(0f, maxX)
    val y = position.y.coerceIn(0f, maxY)

    Surface(
        modifier = Modifier
            .offset(x = x.dp, y = y.dp)
            .width(window.widthDp.dp)
            .then(if (window.heightDp > 0) Modifier.height(window.heightDp.dp) else Modifier),
        shape = RoundedCornerShape(vm.ui.uiPack.theme.cornerRadius.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
        ),
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
                            contentDescription = packText("a11y.closeWindow", "关闭 {title}")
                                    .replace("{title}", window.title),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )

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
internal fun UiNodeView(
    node: UiNode,
    windowId: String,
    vm: AppViewModel,
    onAction: ((String) -> Unit)? = null,
) {
    if (!node.visible || !nodeVisibleWhen(node.visibleWhen, vm)) return
    val pad = Modifier.padding(node.padding.dp)
    val boundText = node.bind.takeIf { it.isNotBlank() }?.let { path ->
        formatRpcValue(resolveUiPath(vm.ui, path), node.format, node.text)
    }

    when (node.type) {
        "column" -> BoxWithConstraints(pad) {
            // A scroll container inside another vertical scroll (window body, sheet,
            // inline panel) would be measured with infinite height. Only scroll when
            // the incoming constraints give us a bounded height.
            val scrollable = node.scroll && constraints.hasBoundedHeight
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (scrollable) {
                            Modifier.verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        },
                    ),
                verticalArrangement = Arrangement.spacedBy(node.spacing.dp),
                horizontalAlignment = when (node.align) {
                    "center" -> Alignment.CenterHorizontally
                    "end" -> Alignment.End
                    else -> Alignment.Start
                },
            ) {
                node.children.forEach { child ->
                    if (child.weight > 0f) {
                        Box(Modifier.weight(child.weight)) { UiNodeView(child, windowId, vm, onAction) }
                    } else {
                        UiNodeView(child, windowId, vm, onAction)
                    }
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
                    Box(Modifier.weight(child.weight)) { UiNodeView(child, windowId, vm, onAction) }
                } else {
                    UiNodeView(child, windowId, vm, onAction)
                }
            }
        }

        "text" -> if (node.style == "markdown") {
            PigMarkdown(
                text = boundText ?: node.text,
                modifier = pad.fillMaxWidth(),
                types = vm.ui.uiPack.types,
                cornerRadius = vm.ui.uiPack.theme.cornerRadius,
            )
        } else {
            val body: @Composable () -> Unit = {
                Text(
                    boundText ?: node.text,
                    modifier = pad,
                    style = textStyle(node.style),
                    color = textColor(node.style),
                    textAlign = when (node.align) {
                        "center" -> TextAlign.Center
                        "end" -> TextAlign.End
                        else -> TextAlign.Start
                    },
                )
            }
            // `danger` is what provider-ui and friends use for failures; those
            // strings are the ones a user wants to copy into a bug report.
            if (node.style == "danger") {
                SelectionContainer { body() }
            } else {
                body()
            }
        }

        "icon" -> {
            val tint = nodeColor(node.color)
            val size = (node.size.takeIf { it > 0 } ?: 18).dp
            Icon(
                packIcon(node.icon.ifBlank { "more" }, node.icon.ifBlank { "more" }),
                contentDescription = node.description.takeIf { it.isNotBlank() },
                modifier = pad
                    .size(size)
                    .then(
                        if (node.action.isNotBlank()) {
                            Modifier.clickable {
                                onAction?.invoke(node.action)
                                    ?: vm.handleWindowAction(windowId, node.action)
                            }
                        } else {
                            Modifier
                        },
                    ),
                tint = tint,
            )
        }

        "avatar" -> Box(
            pad
                .size((node.size.takeIf { it > 0 } ?: 36).dp)
                .then(
                    if (node.action.isNotBlank()) {
                        Modifier.clickable {
                            onAction?.invoke(node.action)
                                ?: vm.handleWindowAction(windowId, node.action)
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            BrandAvatar((node.size.takeIf { it > 0 } ?: 36).dp)
        }

        "connection" -> Row(
            pad,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionDot(vm.ui.connected)
            val label = node.text.ifBlank { node.label }
            if (label.isNotBlank()) {
                Spacer(Modifier.width(5.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = nodeColor(node.color),
                )
            }
        }

        "image" -> {
            val corner = if (node.imageCorner > 0) {
                node.imageCorner
            } else {
                (vm.ui.uiPack.theme.cornerRadius / 2).coerceAtLeast(4)
            }
            val shape = RoundedCornerShape(corner.dp)
            val hash = vm.assetHash(node.src)
            val bitmap = vm.cachedImage(node.src, hash)
            LaunchedEffect(node.src, hash) { vm.loadImage(node.src, hash) }
            val sizeModifier = when {
                node.imageWidth > 0 && node.imageHeight > 0 ->
                    Modifier.size(node.imageWidth.dp, node.imageHeight.dp)
                node.imageWidth > 0 -> Modifier.width(node.imageWidth.dp)
                node.imageHeight > 0 -> Modifier.height(node.imageHeight.dp)
                else -> Modifier.width(200.dp)
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = node.alt.ifBlank { null },
                    modifier = pad.then(sizeModifier).clip(shape),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Box(
                    pad
                        .then(sizeModifier)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        node.alt.ifBlank {
                            if (node.src.isBlank()) "no image" else node.src.substringAfterLast('/')
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        "code" -> Box(
            pad
                .fillMaxWidth()
                .clip(RoundedCornerShape((vm.ui.uiPack.theme.cornerRadius / 2).coerceAtLeast(4).dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
        ) {
            Text(
                boundText ?: node.text,
                fontFamily = packCodeFont(),
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
            val onClick = {
                onAction?.invoke(node.action) ?: vm.handleWindowAction(windowId, node.action)
            }
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
            shape = RoundedCornerShape(12.dp),
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
                UiNodeView(child, windowId, vm, onAction)
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
    "mono" -> MaterialTheme.typography.bodyMedium.copy(fontFamily = packCodeFont())
    else -> MaterialTheme.typography.bodyLarge
}

@Composable
private fun nodeVisibleWhen(condition: String, vm: AppViewModel): Boolean = when (condition) {
    "busy" -> vm.ui.busy
    "idle" -> !vm.ui.busy
    "connected" -> vm.ui.connected
    "offline" -> !vm.ui.connected
    "streaming" -> vm.ui.streamingId != null
    "compacting" -> vm.ui.status == "compacting" || vm.ui.contextCompacted
    else -> true
}

@Composable
private fun nodeColor(name: String): Color = when (name) {
    "primary" -> MaterialTheme.colorScheme.primary
    "secondary" -> MaterialTheme.colorScheme.secondary
    "tertiary" -> MaterialTheme.colorScheme.tertiary
    "onSurface" -> MaterialTheme.colorScheme.onSurface
    "error" -> MaterialTheme.colorScheme.error
    "success" -> MaterialTheme.colorScheme.tertiary
    "warning" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun textColor(style: String) = when (style) {
    "muted" -> MaterialTheme.colorScheme.onSurfaceVariant
    "danger" -> MaterialTheme.colorScheme.error
    "label" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}
