package lt.kantrybes.tvremote.ui.shortcuts

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import lt.kantrybes.tvremote.R
import lt.kantrybes.tvremote.data.settings.AppShortcut
import lt.kantrybes.tvremote.data.settings.Shortcuts

@Composable
internal fun ShortcutGrid(
    shortcuts: List<AppShortcut>,
    onLaunch: (AppShortcut) -> Unit,
    onEdit: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shortcuts.chunked(2).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEachIndexed { column, shortcut ->
                    ShortcutButton(
                        shortcut = shortcut,
                        modifier = Modifier.weight(1f),
                        onLaunch = { onLaunch(shortcut) },
                        onEdit = { onEdit(rowIndex * 2 + column) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortcutButton(
    shortcut: AppShortcut,
    onLaunch: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val description = stringResource(R.string.shortcut_description, shortcut.label)
    Text(
        text = shortcut.label,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .semantics {
                role = Role.Button
                contentDescription = description
            }
            .pointerInput(shortcut) {
                detectTapGestures(
                    onTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLaunch()
                    },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onEdit()
                    },
                )
            }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun ShortcutEditDialog(
    shortcut: AppShortcut,
    onDismiss: () -> Unit,
    onSave: (label: String, packageName: String) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf(shortcut.label) }
    var packageName by rememberSaveable { mutableStateOf(shortcut.packageName) }
    var labelError by rememberSaveable { mutableStateOf(false) }
    var packageError by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shortcut_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = {
                        label = it
                        labelError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.shortcut_label)) },
                    singleLine = true,
                    isError = labelError,
                    supportingText = {
                        if (labelError) Text(stringResource(R.string.shortcut_invalid_label))
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                OutlinedTextField(
                    value = packageName,
                    onValueChange = {
                        packageName = it
                        packageError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.shortcut_package)) },
                    singleLine = true,
                    isError = packageError,
                    supportingText = {
                        if (packageError) Text(stringResource(R.string.shortcut_invalid_package))
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Ascii,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmedLabel = label.trim()
                    val trimmedPackage = packageName.trim()
                    val validLabel = trimmedLabel.isNotEmpty()
                    val validPackage = Shortcuts.isPackageName(trimmedPackage)
                    labelError = !validLabel
                    packageError = !validPackage
                    if (validLabel && validPackage) onSave(trimmedLabel, trimmedPackage)
                },
            ) {
                Text(stringResource(R.string.shortcut_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
