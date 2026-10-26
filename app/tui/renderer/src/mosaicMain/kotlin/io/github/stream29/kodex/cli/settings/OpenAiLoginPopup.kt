package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModel
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.utils.externalurl.OpenExternalUrlResult

@Composable
public actual fun BoxScope.OpenAiLoginPopup(
    viewModel: OpenAiLoginViewModel,
    onDismissRequest: () -> Unit,
    openUrl: suspend (String) -> OpenExternalUrlResult,
) {
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, 72)
    OpenAiLoginContent(
        viewModel, onDismissRequest, openBrowser = { openUrl(it) == OpenExternalUrlResult.Started },
    ) { title, dismiss, content ->
        TuiDialog(
            onDismissRequest = dismiss,
            modifier = Modifier.width(width).background(TuiTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.fillMaxWidth().background(TuiTheme.colorScheme.surfaceContainer)) {
                Text(
                    title, modifier = Modifier.fillMaxWidth().background(TuiTheme.colorScheme.surfaceContainerHigh),
                    color = TuiTheme.colorScheme.onSurface, textStyle = TuiTheme.typography.headline,
                )
                content()
            }
        }
    }
}
