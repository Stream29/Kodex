package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.ui.compose.EditorDialog as Dialog
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModel
import io.github.stream29.kodex.app.sessiondelete.SessionDeleteContent
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsViewModel
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel
import io.github.stream29.kodex.cli.app.ApplicationPopupRenderer
import io.github.stream29.kodex.cli.pathpicker.DirectoryPickerRenderer
import io.github.stream29.kodex.cli.sessioncatalog.SessionCatalogContent
import io.github.stream29.kodex.cli.sessioncatalog.SessionCatalogRenderer
import io.github.stream29.kodex.cli.settings.OpenAiLoginContent
import io.github.stream29.kodex.cli.settings.SettingsContent
import io.github.stream29.kodex.cli.settings.SettingsRootRenderer
import io.github.stream29.kodex.cli.workingdirectory.WorkingDirectoryContent

@Composable
public actual fun rememberComposeApplicationPopupRenderer(
    openExternalUrl: suspend (String) -> Boolean,
): ApplicationPopupRenderer {
    val open = rememberUpdatedState(openExternalUrl)
    val settings = rememberComposeSettingsRootRenderer()
    val catalog = rememberComposeSessionCatalogRenderer()
    val directory = rememberComposeDirectoryPickerRenderer()
    return remember(open, settings, catalog, directory) { ComposeApplicationPopups(open, settings, catalog, directory) }
}

private class ComposeApplicationPopups(
    private val open: State<suspend (String) -> Boolean>,
    private val settings: SettingsRootRenderer,
    private val catalog: SessionCatalogRenderer,
    private val directory: DirectoryPickerRenderer,
) : ApplicationPopupRenderer {
    @Composable
    override fun Catalog(viewModel: SessionCatalogViewModel, onFailure: (Throwable) -> Unit) {
        SessionCatalogContent(viewModel, catalog, onFailure)
    }
    @Composable
    override fun Settings(viewModel: SettingsViewModel, onClose: () -> Unit, onLogin: () -> Unit) {
        SettingsContent(viewModel, settings, onClose, onLogin, LocalGraphicalHostCapabilities.current)
    }
    @Composable
    override fun Rename(viewModel: SessionRenameViewModel, onClose: () -> Unit) { settings.Rename(viewModel, onClose) }
    @Composable
    override fun Delete(viewModel: SessionDeleteViewModel, onClose: () -> Unit) {
        SessionDeleteContent(viewModel, onClose, onResult = { onClose() }) { content ->
            ApplicationDialog("Delete Session", onClose, content)
        }
    }
    @Composable
    override fun Login(viewModel: OpenAiLoginViewModel, onClose: () -> Unit) {
        if (!LocalGraphicalHostCapabilities.current.providerLogin) {
            ApplicationDialog("Provider sign-in unavailable", onClose) {
                UiText("Sign in using the Native client.")
                io.github.stream29.kodex.app.ui.UiAction("Close", onClose)
            }
            return
        }
        OpenAiLoginContent(viewModel, onClose, { open.value(it) }) { title, dismiss, content ->
            ApplicationDialog(title, dismiss, content)
        }
    }
    @Composable
    override fun Directory(viewModel: WorkingDirectoryViewModel, onClose: () -> Unit) {
        WorkingDirectoryContent(viewModel, directory, onClose, onSelected = onClose)
    }
}

@Composable
private fun ApplicationDialog(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                Modifier.widthIn(max = 760.dp).heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                UiText(title, UiTextRole.Title)
                content()
            }
        }
    }
}
