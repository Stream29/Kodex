package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModel
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.tuiColorSchemeFor
import io.github.stream29.kodex.cli.sessioncatalog.SessionCatalogPopup
import io.github.stream29.kodex.cli.sessiondelete.SessionDeletePopup
import io.github.stream29.kodex.cli.sessionrename.SessionRenamePopup
import io.github.stream29.kodex.cli.settings.OpenAiLoginPopup
import io.github.stream29.kodex.cli.settings.SettingsPopup
import io.github.stream29.kodex.cli.workingdirectory.WorkingDirectoryPopup

@Composable
public actual fun BoxScope.rememberMosaicApplicationPopupRenderer(): ApplicationPopupRenderer =
    remember(this) { MosaicApplicationPopups(this) }

private class MosaicApplicationPopups(private val scope: BoxScope) : ApplicationPopupRenderer {
    @Composable
    override fun Catalog(viewModel: SessionCatalogViewModel, onFailure: (Throwable) -> Unit) {
        with(scope) { SessionCatalogPopup(viewModel, onFailure) }
    }
    @Composable
    override fun Settings(viewModel: SettingsViewModel, onClose: () -> Unit, onLogin: () -> Unit) {
        with(scope) {
            TuiTheme(colorScheme = tuiColorSchemeFor(LocalTerminalState.current.theme)) {
                SettingsPopup(viewModel, onClose, onLogin)
            }
        }
    }
    @Composable
    override fun Rename(viewModel: SessionRenameViewModel, onClose: () -> Unit) {
        with(scope) { SessionRenamePopup(viewModel, onClose, onClose) }
    }
    @Composable
    override fun Delete(viewModel: SessionDeleteViewModel, onClose: () -> Unit) {
        with(scope) { SessionDeletePopup(viewModel, onClose, onResult = { onClose() }) }
    }
    @Composable
    override fun Login(viewModel: OpenAiLoginViewModel, onClose: () -> Unit) {
        with(scope) { OpenAiLoginPopup(viewModel, onClose) }
    }
    @Composable
    override fun Directory(viewModel: WorkingDirectoryViewModel, onClose: () -> Unit) {
        with(scope) { WorkingDirectoryPopup(viewModel, onClose, onClose) }
    }
}
