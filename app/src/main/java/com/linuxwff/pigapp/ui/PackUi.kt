package com.linuxwff.pigapp.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.linuxwff.pigapp.UiComponents
import com.linuxwff.pigapp.UiTheme

/** The built-in icon names a UI pack can reference from `icons.json`. */
fun packIconVector(name: String): ImageVector? = when (name.lowercase()) {
    "menu" -> Icons.Default.Menu
    "send" -> Icons.Default.Send
    "stop" -> Icons.Default.Stop
    "refresh" -> Icons.Default.Refresh
    "add" -> Icons.Default.Add
    "settings" -> Icons.Default.Settings
    "build", "tool" -> Icons.Default.Build
    "check" -> Icons.Default.Check
    "delete" -> Icons.Default.Delete
    "edit" -> Icons.Default.Edit
    "more" -> Icons.Default.MoreVert
    "search" -> Icons.Default.Search
    "copy" -> Icons.Default.ContentCopy
    "history" -> Icons.Default.History
    "down" -> Icons.Default.KeyboardArrowDown
    "up" -> Icons.Default.KeyboardArrowUp
    "close" -> Icons.Default.Close
    "warning" -> Icons.Default.Warning
    else -> null
}

/** Resolve `icons.<key>` from the pack, falling back to a built-in name. */
@Composable
fun packIcon(key: String, fallback: String): ImageVector =
    packIconVector(LocalUiPack.current.icons[key] ?: fallback)
        ?: packIconVector(fallback)
        ?: Icons.Default.Build

@Composable
fun packText(key: String, fallback: String): String =
    LocalUiPack.current.strings.get(key, fallback)

@Composable
fun packComponents(): UiComponents = LocalUiPack.current.components

@Composable
fun packTheme(): UiTheme = LocalUiPack.current.theme
