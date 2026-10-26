package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.contextsourcesettings.ContextSourceSettingsViewModel
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiTheme

@Composable
public actual fun BoxScope.ContextSourceSettingsComponent(
    viewModel: ContextSourceSettingsViewModel, showOperationFailure: Boolean,
) {
    ContextSourceSettingsPanel(viewModel, showOperationFailure)
    ContextSourceSettingsDialogs(viewModel)
}

@Composable
public actual fun ContextSourceSettingsPanel(viewModel: ContextSourceSettingsViewModel, showOperationFailure: Boolean) {
    ContextSourceSettingsContent(viewModel, remember { MosaicSettingsRenderer(emptyMap()) }, showOperationFailure)
}

@Composable
public actual fun BoxScope.ContextSourceSettingsDialogs(viewModel: ContextSourceSettingsViewModel) {
    ContextSourceSettingsDialogHost(viewModel) { title, dismiss, content -> ContextSourceDialogFrame(title, dismiss, content) }
}

@Composable
internal fun BoxScope.ContextSourceDialogFrame(title: String, dismiss: () -> Unit, content: @Composable () -> Unit) {
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, 96)
    TuiDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.width(width).background(SettingsDialogBackground),
    ) {
        Column(Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
            Text(
                title, modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground, textStyle = TuiTheme.typography.headline,
            )
            content()
        }
    }
}
