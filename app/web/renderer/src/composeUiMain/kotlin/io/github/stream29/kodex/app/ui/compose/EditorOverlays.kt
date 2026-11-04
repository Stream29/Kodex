package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.stream29.kodex.app.ui.UiEditorOverlay

@Composable
internal fun DirectionalFocusBoundary(content: @Composable () -> Unit) {
    Box(Modifier.directionalFocusFallback()) { content() }
}

private fun Modifier.directionalFocusFallback(): Modifier = composed {
    val focus = LocalFocusManager.current
    onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown ||
            event.isShiftPressed || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
        ) false else {
            val direction = when (event.key) {
                Key.DirectionLeft -> FocusDirection.Left
                Key.DirectionRight -> FocusDirection.Right
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                else -> null
            }
            direction?.let(focus::moveFocus) ?: false
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun EditorDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    // DOM leaves cannot take part in Dialog's captured-bitmap exit animation.
    // Dispose the layer with its content, before underlying editors can reappear.
    val interopProperties = remember(properties) {
        DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            usePlatformDefaultWidth = properties.usePlatformDefaultWidth,
            usePlatformInsets = properties.usePlatformInsets,
            useSoftwareKeyboardInset = properties.useSoftwareKeyboardInset,
            scrimColor = properties.scrimColor,
            animateTransition = false,
        )
    }
    androidx.compose.ui.window.Dialog(onDismissRequest, interopProperties) {
        UiEditorOverlay {
            val focus = remember { FocusRequester() }
            var placed by remember { mutableStateOf(false) }
            var hasFocus by remember { mutableStateOf(false) }
            LaunchedEffect(placed) {
                if (placed) {
                    withFrameNanos { }
                    if (!hasFocus) focus.requestFocus(FocusDirection.Enter)
                }
            }
            Box(Modifier.directionalFocusFallback().focusRequester(focus)
                .onFocusChanged { hasFocus = it.hasFocus }.focusGroup()
                .onGloballyPositioned { placed = it.isAttached }) { content() }
        }
    }
}

@Composable
internal fun EditorPopup(
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.window.Popup(popupPositionProvider, onDismissRequest, properties) {
        UiEditorOverlay { DirectionalFocusBoundary(content) }
    }
}

@Composable
internal fun EditorDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.DropdownMenu(expanded, onDismissRequest, modifier.directionalFocusFallback()) {
        val column = this
        UiEditorOverlay { column.content() }
    }
}
