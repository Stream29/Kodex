package io.github.stream29.codex.lite.cli.components

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

/**
 * State shared by a [TuiDropdownTrigger] and its host-level [TuiDropdownMenu].
 *
 * Keep the menu as a direct child of the surrounding [TuiPopupHost] so it can render above
 * dialogs and other persistent content without inheriting the trigger's layout constraints.
 */
@Stable
public class TuiDropdownState internal constructor() {
    internal val anchor: TuiPopupAnchor = TuiPopupAnchor()

    /** Whether the associated menu is currently displayed. */
    public var expanded: Boolean by mutableStateOf(false)
        private set

    /** Opens the associated menu. */
    public fun expand() {
        expanded = true
    }

    /** Closes the associated menu. */
    public fun dismiss() {
        expanded = false
    }
}

/** Creates [TuiDropdownState] that survives recomposition at this call site. */
@Composable
public fun rememberTuiDropdownState(): TuiDropdownState = remember { TuiDropdownState() }

/**
 * Renders a popup-menu trigger for [dropdownState].
 *
 * Removing or disabling the trigger dismisses its menu so a stale menu cannot survive a route or
 * configuration change.
 */
@Composable
public fun TuiDropdownTrigger(
    dropdownState: TuiDropdownState,
    label: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    enabled: Boolean = true,
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
        enabled = enabled,
        onClick = dropdownState::expand,
    )
}

/**
 * Displays a menu for [dropdownState] over the surrounding [TuiPopupHost].
 *
 * Call this after persistent content, including the matching [TuiDropdownTrigger].
 */
@Composable
public fun BoxScope.TuiDropdownMenu(
    dropdownState: TuiDropdownState,
    positionProvider: TuiPopupPositionProvider = TuiPopupPositionProvider.AboveStart,
    backgroundColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
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

/** Displays one selectable menu item for each [options] value. */
@Composable
public fun <T : Any> BoxScope.TuiDropdownMenu(
    dropdownState: TuiDropdownState,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
    positionProvider: TuiPopupPositionProvider = TuiPopupPositionProvider.AboveStart,
    backgroundColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
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
