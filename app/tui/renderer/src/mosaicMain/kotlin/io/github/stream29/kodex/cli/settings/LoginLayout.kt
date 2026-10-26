package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiInteractionStyle
import io.github.stream29.kodex.cli.components.TuiTheme

internal object MosaicLoginLayoutRenderer : LoginLayoutRenderer {
    @Composable
    override fun LoginBodyFrame(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().background(TuiTheme.colorScheme.surfaceContainer)) { content() }
    }

    @Composable
    override fun LoginActions(primaryLabel: String?, primaryAction: (() -> Unit)?, onDismissRequest: (() -> Unit)?) {
        require((primaryLabel == null) == (primaryAction == null)) {
            "A primary label and action must be supplied together."
        }
        TuiDialogActionRow(Modifier.fillMaxWidth().background(TuiTheme.colorScheme.surfaceContainerHigh)) {
            onDismissRequest?.let { dismiss ->
                TuiButton(
                    label = "Cancel", color = TuiTheme.colorScheme.primary,
                    interactionStyle = TuiInteractionStyle.PreserveColors, onClick = dismiss,
                )
            }
            primaryLabel?.let { label ->
                TuiButton(
                    label = label, modifier = Modifier.background(TuiTheme.colorScheme.primary),
                    color = TuiTheme.colorScheme.onPrimary, interactionStyle = TuiInteractionStyle.PreserveColors,
                    onClick = requireNotNull(primaryAction),
                )
            }
        }
    }
}
