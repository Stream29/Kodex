package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.stream29.kodex.app.ui.UiEditorOverlay

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
    androidx.compose.ui.window.Dialog(onDismissRequest, interopProperties) { UiEditorOverlay(content) }
}

@Composable
internal fun EditorPopup(
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.window.Popup(popupPositionProvider, onDismissRequest, properties) { UiEditorOverlay(content) }
}

@Composable
internal fun EditorDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.DropdownMenu(expanded, onDismissRequest, modifier) {
        val column = this
        UiEditorOverlay { column.content() }
    }
}
