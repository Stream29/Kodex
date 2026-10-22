@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Text

@Stable
public actual class TuiDropdownState internal actual constructor() {
    internal val anchor: TuiPopupAnchor = TuiPopupAnchor()

    /** Whether the associated menu is currently displayed. */
    public actual var expanded: Boolean by mutableStateOf(false)
        private set

    public actual fun expand() {
        expanded = true
    }

    public actual fun dismiss() {
        expanded = false
    }
}

@Composable
public actual fun rememberTuiDropdownState(): TuiDropdownState = remember { TuiDropdownState() }

@Composable
public actual fun TuiDropdownTrigger(
    dropdownState: TuiDropdownState,
    label: String,
    modifier: Modifier,
    color: Color,
    enabled: Boolean,
    autoFocus: Boolean,
    interactionStyle: TuiInteractionStyle,
) {
    DisposableEffect(dropdownState) {
        onDispose { dropdownState.dismiss() }
    }
    LaunchedEffect(enabled) {
        if (!enabled) dropdownState.dismiss()
    }
    TuiButton(
        label = label,
        modifier = modifier.tuiPopupAnchor(dropdownState.anchor),
        color = color,
        interactionStyle = interactionStyle,
        enabled = enabled,
        autoFocus = autoFocus,
        onClick = dropdownState::expand,
    )
}

@Composable
public actual fun BoxScope.TuiDropdownMenu(
    dropdownState: TuiDropdownState,
    positionProvider: TuiPopupPositionProvider,
    backgroundColor: Color,
    modifier: Modifier,
    content: TuiPopupMenuScope.() -> Unit,
) {
    if (!dropdownState.expanded) return
    TuiPopupMenu(
        expanded = true,
        onDismissRequest = dropdownState::dismiss,
        anchor = dropdownState.anchor,
        positionProvider = positionProvider,
        backgroundColor = backgroundColor,
        modifier = modifier,
        content = content,
    )
}

@Composable
public actual fun <T : Any> BoxScope.TuiDropdownMenu(
    dropdownState: TuiDropdownState,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean,
    positionProvider: TuiPopupPositionProvider,
    backgroundColor: Color,
    modifier: Modifier,
) {
    TuiDropdownMenu(
        dropdownState = dropdownState,
        positionProvider = positionProvider,
        backgroundColor = backgroundColor,
        modifier = modifier,
        content = {
            options.forEach { option ->
                TuiPopupMenuItem(
                    key = option,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    selected = option == selected,
                ) {
                    Text(optionLabel(option))
                }
            }
        },
    )
}
