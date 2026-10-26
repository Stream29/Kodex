package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.cli.settings.McpSettingsEffects
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
public actual fun ApplicationPopupContent(
    viewModel: ApplicationViewModel,
    popup: ApplicationPopupState,
    renderer: ApplicationPopupRenderer,
    onOperationFailure: (Throwable) -> Unit,
    openMcpUrl: suspend (String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val report by rememberUpdatedState(onOperationFailure)
    val settingsOwner = when (popup) {
        is ApplicationPopupState.Settings -> popup
        is ApplicationPopupState.Login -> popup.returnTo
        else -> null
    }
    if (settingsOwner != null) key(PopupOwnerKey(settingsOwner)) {
        McpSettingsEffects(settingsOwner.viewModel.global.mcpSettings, openMcpUrl)
    }
    key(PopupOwnerKey(popup)) {
        when (popup) {
            ApplicationPopupState.Closed -> Unit
            is ApplicationPopupState.SessionCatalog -> renderer.Catalog(popup.viewModel, onOperationFailure)
            is ApplicationPopupState.Settings -> renderer.Settings(
                popup.viewModel,
                onClose = { viewModel.dismissPopup(popup) },
                onLogin = {
                    scope.launch {
                        try { viewModel.openLoginPopup(popup) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Throwable) { report(failure) }
                    }
                },
            )
            is ApplicationPopupState.RenameSession -> renderer.Rename(popup.viewModel) { viewModel.dismissPopup(popup) }
            is ApplicationPopupState.DeleteSession -> renderer.Delete(popup.viewModel) { viewModel.dismissPopup(popup) }
            is ApplicationPopupState.Login -> renderer.Login(popup.viewModel) { viewModel.dismissPopup(popup) }
            is ApplicationPopupState.WorkingDirectory -> renderer.Directory(popup.viewModel) { viewModel.dismissPopup(popup) }
        }
    }
}

private class PopupOwnerKey(private val owner: Any) {
    override fun equals(other: Any?): Boolean = other is PopupOwnerKey && other.owner === owner
    override fun hashCode(): Int = 0
}
