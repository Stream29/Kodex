package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModel
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel

/** Component mounting and modal geometry only; exact open-handle wiring belongs to common content. */
public interface ApplicationPopupRenderer {
    @Composable public fun Catalog(viewModel: SessionCatalogViewModel, onFailure: (Throwable) -> Unit)
    @Composable public fun Settings(viewModel: SettingsViewModel, onClose: () -> Unit, onLogin: () -> Unit)
    @Composable public fun Rename(viewModel: SessionRenameViewModel, onClose: () -> Unit)
    @Composable public fun Delete(viewModel: SessionDeleteViewModel, onClose: () -> Unit)
    @Composable public fun Login(viewModel: OpenAiLoginViewModel, onClose: () -> Unit)
    @Composable public fun Directory(viewModel: WorkingDirectoryViewModel, onClose: () -> Unit)
}

/**
 * Renders the exact popup snapshot and retains one MCP consumer under Settings across Login.
 * Adds no child close policy: existing component contracts still govern their transient disposal.
 * Ordinary login-opening failures use [onOperationFailure]; cancellation is propagated.
 */
@Composable
public expect fun ApplicationPopupContent(
    viewModel: ApplicationViewModel,
    popup: ApplicationPopupState,
    renderer: ApplicationPopupRenderer,
    onOperationFailure: (Throwable) -> Unit,
    openMcpUrl: suspend (String) -> Boolean,
): Unit
