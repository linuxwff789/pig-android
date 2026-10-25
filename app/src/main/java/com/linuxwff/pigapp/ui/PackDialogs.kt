package com.linuxwff.pigapp.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * `AlertDialog` with the pack's `chrome` colours applied. Parameter list matches
 * the Material component so call sites only swap the name.
 */
@Composable
fun PackAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val chrome = LocalUiPack.current.layout.chrome
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.then(
            if (chrome.dialogMaxWidth > 0) {
                Modifier.widthIn(max = chrome.dialogMaxWidth.dp)
            } else {
                Modifier
            },
        ),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = if (chrome.dialogRadius >= 0) {
            RoundedCornerShape(chrome.dialogRadius.dp)
        } else {
            AlertDialogDefaults.shape
        },
        containerColor = if (chrome.dialogSurface.isBlank()) {
            AlertDialogDefaults.containerColor
        } else {
            packColor(chrome.dialogSurface)
        },
        iconContentColor = packColor(chrome.dialogTitleColor),
        titleContentColor = packColor(chrome.dialogTitleColor),
        textContentColor = packColor(chrome.dialogTextColor),
        properties = properties,
    )
}

@Composable
fun packChipShape(): Shape {
    val chrome = LocalUiPack.current.layout.chrome
    return if (chrome.chipShape == "rounded") {
        RoundedCornerShape((if (chrome.chipRadius >= 0) chrome.chipRadius else 10).dp)
    } else {
        CircleShape
    }
}

@Composable
fun packChipColors(): SelectableChipColors {
    val chrome = LocalUiPack.current.layout.chrome
    val accent = packColor(chrome.chipBackground)
    return FilterChipDefaults.filterChipColors(
        selectedContainerColor = accent.copy(alpha = chrome.chipBackgroundAlpha),
        selectedLabelColor = packColor(chrome.chipLabelColor),
    )
}
