package io.github.stream29.kodex.cli.usagereset

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.settings.contract.UsageResetState
import io.github.stream29.kodex.app.usagereset.contract.UsageResetViewModel
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.settings.SettingsDialogBackground
import io.github.stream29.kodex.cli.settings.SettingsForeground
import io.github.stream29.kodex.cli.settings.SettingsHeaderBackground

@Composable
public actual fun BoxScope.UsageResetDialogHost(viewModel: UsageResetViewModel) {
    UsageResetContent(viewModel) { title, dismiss, content -> UsageResetDialogFrame(title, dismiss, content) }
}

@Composable
public actual fun BoxScope.UsageResetDialog(
    state: UsageResetState,
    onSelect: (String) -> Unit,
    onConfirm: (UsageResetState.Confirming) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    UsageResetContent(state, onSelect, onConfirm, onBack, onRetry, onRefresh, onDismiss) { title, dismiss, content ->
        UsageResetDialogFrame(title, dismiss, content)
    }
}

@Composable
internal fun BoxScope.UsageResetDialogFrame(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, 72)
    TuiDialog(onDismissRequest = onDismiss, modifier = Modifier.width(width).background(SettingsDialogBackground)) {
        Column(Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
            Text(
                title, modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground, textStyle = TuiTheme.typography.headline,
            )
            content()
        }
    }
}
