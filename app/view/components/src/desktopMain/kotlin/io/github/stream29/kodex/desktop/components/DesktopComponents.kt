package io.github.stream29.kodex.desktop.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Keyboard chord used to submit a multiline Desktop composer. */
public enum class DesktopComposerSubmitKey {
    Enter,
    CtrlEnter,
}

/** In-window Material modal layer for custom Desktop popup content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun DesktopModal(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
): Unit {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        content = content,
    )
}

/**
 * Compact Desktop mapping of the TUI composer.
 *
 * The configured newline chord remains available inside the multiline field;
 * its complementary chord submits. Submission intentionally has no visible
 * button because the TUI composer has none.
 */
@Composable
public fun DesktopComposer(
    text: String,
    cursorOffset: Int,
    submitKey: DesktopComposerSubmitKey,
    onValueChange: (text: String, cursorOffset: Int) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    submitEnabled: Boolean = enabled && text.isNotBlank(),
    placeholder: String = "",
    supportingText: String? = null,
    focusRequester: FocusRequester? = null,
    autoFocus: Boolean = false,
): Unit {
    val fieldFocusRequester = focusRequester ?: remember { FocusRequester() }
    var fieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = text,
                selection = TextRange(cursorOffset.coerceIn(0, text.length)),
            ),
        )
    }
    LaunchedEffect(text, cursorOffset) {
        val selection = cursorOffset.coerceIn(0, text.length)
        if (fieldValue.text != text || fieldValue.selection.start != selection) {
            fieldValue = TextFieldValue(text = text, selection = TextRange(selection))
        }
    }
    LaunchedEffect(fieldFocusRequester, autoFocus, enabled) {
        if (autoFocus && enabled) fieldFocusRequester.requestFocus()
    }

    Column(modifier = modifier) {
        TextField(
            value = fieldValue,
            onValueChange = { updated ->
                fieldValue = updated
                onValueChange(updated.text, updated.selection.start)
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocusRequester)
                .onPreviewKeyEvent { event ->
                    val submits = when (submitKey) {
                        DesktopComposerSubmitKey.Enter ->
                            event.key == Key.Enter &&
                                !event.isCtrlPressed &&
                                !event.isShiftPressed

                        DesktopComposerSubmitKey.CtrlEnter ->
                            event.key == Key.Enter &&
                                event.isCtrlPressed &&
                                !event.isShiftPressed
                    }
                    if (
                        event.type == KeyEventType.KeyDown &&
                        submits &&
                        submitEnabled
                    ) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
                },
            enabled = enabled,
            prefix = { Text(">") },
            placeholder = placeholder.takeIf(String::isNotEmpty)?.let { value ->
                { Text(value) }
            },
            minLines = 1,
            maxLines = 6,
            shape = RectangleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        )
        supportingText?.let { value ->
            Text(
                text = value,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Compact dropdown selector used by Desktop runtime and Settings surfaces. */
@Composable
public fun <T> DesktopChoice(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
): Unit {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        FilledTonalButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && options.isNotEmpty(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                if (label.isBlank()) {
                    optionLabel(selected)
                } else {
                    "$label · ${optionLabel(selected)}"
                },
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (option == selected) {
                                "✓ ${optionLabel(option)}"
                            } else {
                                optionLabel(option)
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** Compact single-choice row corresponding to a TUI choice group. */
@Composable
public fun <T> DesktopChoiceGroup(
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
): Unit {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = options.size,
                ),
            ) {
                Text(optionLabel(option))
            }
        }
    }
}

/** Contiguous tonal block corresponding to one TUI settings region. */
@Composable
public fun DesktopSection(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
): Unit {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        shape = RectangleShape,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            content()
        }
    }
}

/** Small semantic state label for secondary status surfaces. */
@Composable
public fun DesktopStatusPill(
    label: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
): Unit {
    Surface(
        modifier = modifier,
        color = if (emphasized) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (emphasized) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Full-width compact message used for empty and recoverable states. */
@Composable
public fun DesktopMessage(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
): Unit {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RectangleShape,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Opens a renderer-owned context menu without adding a visible menu affordance. */
@OptIn(ExperimentalComposeUiApi::class)
public fun Modifier.desktopSecondaryClick(
    focusable: Boolean = true,
    onClick: () -> Unit,
): Modifier =
    then(if (focusable) Modifier.focusable() else Modifier)
        .onPointerEvent(PointerEventType.Press) { event ->
        if (event.buttons.isSecondaryPressed) {
            event.changes.forEach { change -> change.consume() }
            onClick()
        }
        }
        .onPreviewKeyEvent { event ->
            if (
                event.type == KeyEventType.KeyDown &&
                (
                    event.key == Key.Menu ||
                        (event.key == Key.F10 && event.isShiftPressed)
                    )
            ) {
                onClick()
                true
            } else {
                false
            }
        }
