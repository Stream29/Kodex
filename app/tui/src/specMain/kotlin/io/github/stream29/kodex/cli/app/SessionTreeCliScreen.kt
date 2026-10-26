package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.application.contract.SidebarSettingsViewModel
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.utils.externalurl.OpenExternalUrlResult
import io.github.stream29.kodex.utils.externalurl.openExternalUrl
import kotlinx.coroutines.flow.StateFlow

/**
 * Application shell; all mutable child state is collected by its exact renderer.
 * Throwing History/catalog actions reach [onOperationFailure] once. Its default
 * throws for an unwired caller; the real CLI supplies its Application-owned unhandled-error
 * reporter. Cancellation never enters the reporter.
 */
@Composable
public expect fun SessionTreeCliScreen(
    viewModel: ApplicationViewModel,
    newLineKey: StateFlow<NewLineKey>,
    sidebarSettings: SidebarSettingsViewModel,
    onOperationFailure: (Throwable) -> Unit = { throw it },
    reservedRows: Int = 0,
    openMcpUrl: suspend (String) -> Boolean = { url ->
        openExternalUrl(url) !is OpenExternalUrlResult.Failed
    },
): Unit
