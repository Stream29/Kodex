package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.*
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel
import io.github.stream29.kodex.cli.authenticationsettings.AuthenticationLogoutFrame
import io.github.stream29.kodex.cli.components.*
import io.github.stream29.kodex.cli.newsessiondefaults.*
import io.github.stream29.kodex.cli.sessionrename.SessionRenamePopup
import io.github.stream29.kodex.cli.sessionrename.SessionRenamePresentation
import io.github.stream29.kodex.cli.sessionsettings.*
import io.github.stream29.kodex.cli.usagereset.UsageResetDialogFrame
import io.github.stream29.kodex.cli.workingdirectory.WorkingDirectoryPopup

@Composable
public actual fun BoxScope.SettingsPopup(
    viewModel: SettingsViewModel, onDismissRequest: () -> Unit, onOpenLogin: () -> Unit,
) {
    val session = rememberSessionSettingsDropdowns()
    val defaults = rememberNewSessionDefaultsDropdowns()
    val preferences = rememberApplicationPreferencesDropdowns()
    val title = rememberSessionTitleSettingsDropdowns()
    val authentication = rememberTuiDropdownState()
    val renderer = remember(this, session, defaults, preferences, title, authentication) {
        MosaicSettingsRoot(this, session, defaults, preferences, title, authentication)
    }
    SettingsContent(viewModel, renderer, onDismissRequest, onOpenLogin)
}

private class MosaicSettingsRoot(
    private val box: BoxScope,
    private val session: SessionSettingsDropdowns,
    private val defaults: NewSessionDefaultsDropdowns,
    private val preferences: ApplicationPreferencesDropdowns,
    private val title: SessionTitleSettingsDropdowns,
    private val authentication: TuiDropdownState,
) : SettingsRootRenderer {
    private val authFields = MosaicSettingsRenderer(
        mapOf("AuthenticationSource" to authentication), inlineTriggerKeys = setOf("AuthenticationSource"),
    )
    override val fields = SettingsPageFields(preferences.renderer, title.renderer, defaults.renderer, session.renderer, authFields)
    override val hooks: HookDialogRenderer = MosaicHookDialogs(box)
    override val mcp: McpDialogRenderer = MosaicMcpDialogs(box)
    override val frontendErrorHooksAvailable: Boolean = true
    override val frontendErrorHooksLabel: String = "TUI local errors"

    @Composable
    override fun PreparePage(page: SettingsPage) {
        LaunchedEffect(page) {
            session.dismissAll()
            defaults.dismissAll()
            preferences.newLineKey.dismiss(); preferences.submitKey.dismiss()
            title.model.dismiss(); title.reasoning.dismiss()
            authentication.dismiss()
        }
    }

    @Composable
    override fun Frame(
        selectedPage: SettingsPage, navigation: List<SettingsNavigationEntry>,
        onSelect: (SettingsPage) -> Unit, onDismiss: () -> Unit,
        banner: @Composable () -> Unit, content: @Composable () -> Unit,
    ) = with(box) {
        val terminalSize = LocalTerminalState.current.size
        val width = (terminalSize.columns - 4).coerceIn(1, SettingsMaximumWidth)
        val height = (terminalSize.rows - 4).coerceAtLeast(1)
        val scroll = remember(selectedPage) { ScrollState() }
        val navigationWidth = SettingsNavigationWidth.coerceAtMost((width - 1).coerceAtLeast(1))
        val contentWidth = (width - navigationWidth).coerceAtLeast(1)
        TuiDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.width(width).height(height).background(SettingsDialogBackground),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Settings", Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                    color = SettingsForeground, textStyle = TuiTheme.typography.headline,
                )
                banner()
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.width(navigationWidth).fillMaxHeight().background(SettingsNavigationBackground)) {
                        navigation.forEach { entry ->
                            SettingsNavigationButton(
                                entry.label, modifier = Modifier.fillMaxWidth(), selected = entry.page == selectedPage,
                                onClick = { onSelect(entry.page) },
                            )
                        }
                    }
                    SettingsPageViewport(contentWidth, scroll, content)
                }
                TuiDialogActionRow(Modifier.fillMaxWidth().background(SettingsActionBackground)) {
                    SettingsActionButton("Close", onClick = onDismiss)
                }
            }
        }
    }

    @Composable
    override fun Menus(group: SettingsFieldGroup) = with(box) {
        val renderer = when (group) {
            SettingsFieldGroup.Preferences -> preferences.renderer
            SettingsFieldGroup.Title -> title.renderer
            SettingsFieldGroup.Defaults -> defaults.renderer
            SettingsFieldGroup.Session -> session.renderer
            SettingsFieldGroup.Authentication -> authFields
        }
        with(renderer) { Menus() }
    }

    @Composable
    override fun FailureBanner(onDismiss: () -> Unit) { SettingsOperationFailureBanner(true, onDismiss) }

    @Composable
    override fun Modal(kind: SettingsModalFrame, title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) = with(box) {
        when (kind) {
            SettingsModalFrame.ContextSource -> ContextSourceDialogFrame(title, onDismiss, content)
            SettingsModalFrame.Logout -> AuthenticationLogoutFrame(title, onDismiss, content)
            SettingsModalFrame.UsageReset -> UsageResetDialogFrame(title, onDismiss, content)
        }
    }

    @Composable
    override fun Rename(viewModel: SessionRenameViewModel, onDismiss: () -> Unit) = with(box) {
        SessionRenamePopup(viewModel, onDismiss, onDismiss, SessionRenamePresentation.Labeled)
    }

    @Composable
    override fun Directory(viewModel: WorkingDirectoryViewModel, onDismiss: () -> Unit) = with(box) {
        WorkingDirectoryPopup(viewModel, onDismiss, {})
    }
}

@Composable
internal fun SettingsOperationFailureBanner(failed: Boolean, onDismiss: () -> Unit) {
    if (!failed) return
    Column(Modifier.fillMaxWidth().background(SettingsActionBackground)) {
        Text(SettingsOperationFailureMessage, color = SettingsErrorForeground)
        SettingsActionButton("Dismiss", onClick = onDismiss)
    }
}

@Composable
internal fun SettingsPageViewport(width: Int, scrollState: ScrollState, content: @Composable () -> Unit) {
    Column(
        Modifier.width(width).fillMaxHeight().background(SettingsHomeBackground).verticalScroll(scrollState),
    ) { content() }
}

private const val SettingsMaximumWidth: Int = 84
private const val SettingsNavigationWidth: Int = 18
