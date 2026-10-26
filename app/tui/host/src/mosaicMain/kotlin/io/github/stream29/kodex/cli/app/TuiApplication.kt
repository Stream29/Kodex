package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jakewharton.mosaic.runMosaic
import com.jakewharton.mosaic.terminal.MouseTracking
import com.jakewharton.mosaic.terminal.TerminalScreen
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.layout.height
import io.github.stream29.kodex.rpc.local.BackendConnectionState

public actual suspend fun runTuiApplication(application: KodexApplication) {
    try {
        runMosaic(mouseTracking = MouseTracking.AnyEvents, screen = TerminalScreen.Alternate) {
            val state by application.connectionState.collectAsState()
            val failureReason by application.connectionFailureReason.collectAsState()
            Column {
                if (state != BackendConnectionState.Connected) {
                    Text(
                        if (state == BackendConnectionState.Failed) failureReason?.let { "Backend failed: $it" }
                            ?: "Backend ended or changed. Restart this TUI."
                        else "Backend disconnected. Reconnecting to the same instance...",
                        modifier = Modifier.height(1),
                        color = Color.Yellow,
                    )
                }
                SessionTreeCliScreen(
                    viewModel = application.viewModel,
                    newLineKey = application.newLineKey,
                    sidebarSettings = application.sidebarSettings,
                    onOperationFailure = application.reportUnhandledError,
                    reservedRows = if (state == BackendConnectionState.Connected) 0 else 1,
                )
            }
        }
    } finally { resetTerminalTitle() }
}
