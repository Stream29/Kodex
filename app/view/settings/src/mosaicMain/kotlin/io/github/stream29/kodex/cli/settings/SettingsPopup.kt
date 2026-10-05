package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.*
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.cli.accountusage.AccountUsageComponent
import io.github.stream29.kodex.cli.authenticationsettings.AuthenticationSettingsOverlays
import io.github.stream29.kodex.cli.authenticationsettings.AuthenticationSettingsPanel
import io.github.stream29.kodex.cli.components.*
import io.github.stream29.kodex.cli.newsessiondefaults.*
import io.github.stream29.kodex.cli.sessionsettings.*
import io.github.stream29.kodex.cli.usagereset.UsageResetDialogHost

/**
 * Navigation and scroll layout for stable Settings children, with direct popup-host overlays.
 * All configuration, drafts and exact interaction targets belong to the components. The host renders
 * shared failure once and hides page interactions without closing their observation lifetimes.
 */
@Composable
public fun BoxScope.SettingsPopup(
    viewModel: SettingsViewModel,
    onDismissRequest: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val selectedPage by viewModel.selectedPage.collectAsState()
    val operationFailed by viewModel.global.operationFailure.collectAsState()
    val sessionDropdowns = rememberSessionSettingsDropdowns()
    val defaultsDropdowns = rememberNewSessionDefaultsDropdowns()
    val preferencesDropdowns = rememberApplicationPreferencesDropdowns()
    val titleDropdowns = rememberSessionTitleSettingsDropdowns()
    val authenticationDropdown = rememberTuiDropdownState()
    val currentOpenLogin by rememberUpdatedState(onOpenLogin)
    LaunchedEffect(viewModel.global) {
        viewModel.global.effects.collect { effect ->
            when (effect) { GlobalSettingsEffect.OpenLogin -> currentOpenLogin() }
        }
    }
    LaunchedEffect(selectedPage) {
        sessionDropdowns.dismissAll()
        defaultsDropdowns.dismissAll()
        preferencesDropdowns.newLineKey.dismiss()
        preferencesDropdowns.submitKey.dismiss()
        titleDropdowns.model.dismiss()
        titleDropdowns.reasoning.dismiss()
        authenticationDropdown.dismiss()
    }

    val terminalSize = LocalTerminalState.current.size
    val width = (terminalSize.columns - 4).coerceIn(1, SettingsMaximumWidth)
    val height = (terminalSize.rows - 4).coerceAtLeast(1)
    val pageScrollState = remember(selectedPage) { ScrollState() }
    val navigationWidth = SettingsNavigationWidth.coerceAtMost((width - 1).coerceAtLeast(1))
    val contentWidth = (width - navigationWidth).coerceAtLeast(1)
    TuiDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(width).height(height).background(SettingsDialogBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                value = "Settings",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            SettingsOperationFailureBanner(operationFailed, viewModel.global::dismissOperationFailure)
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    modifier = Modifier.width(navigationWidth).fillMaxHeight()
                        .background(SettingsNavigationBackground),
                ) {
                    SettingsPage.entries.forEach { candidate ->
                        SettingsNavigationButton(
                            label = candidate.settingsLabel(), modifier = Modifier.fillMaxWidth(),
                            selected = candidate == selectedPage,
                            onClick = { viewModel.selectPage(candidate) },
                        )
                    }
                }
                SettingsPageViewport(contentWidth, pageScrollState) {
                    when (selectedPage) {
                        SettingsPage.General -> ApplicationPreferencesPanel(
                            viewModel.global.applicationPreferences, preferencesDropdowns,
                            showOperationFailure = false,
                        )
                        SettingsPage.ContextSources -> ContextSourceSettingsPanel(
                            viewModel.global.contextSourceSettings, showOperationFailure = false,
                        )
                        SettingsPage.OpenAi -> SettingsSection(title = "Account") {
                            AuthenticationSettingsPanel(
                                viewModel.global.authenticationSettings, authenticationDropdown,
                                showOperationFailure = false,
                            )
                            AccountUsageComponent(viewModel.global.accountUsage, showOperationFailure = false)
                        }
                        SettingsPage.Mcp -> McpSettingsPanel(viewModel.global.mcpSettings, showOperationFailure = false)
                        SettingsPage.Hooks -> HookSettingsPanel(viewModel.global.hookSettings, showOperationFailure = false)
                        SettingsPage.CurrentSession -> SessionSettingsPanel(viewModel.session, sessionDropdowns)
                        SettingsPage.NewSession -> {
                            NewSessionDefaultsPanel(viewModel.newSession, defaultsDropdowns, showOperationFailure = false)
                            SessionTitleSettingsPanel(
                                viewModel.global.sessionTitleSettings, titleDropdowns, showOperationFailure = false,
                            )
                        }
                    }
                }
            }
            TuiDialogActionRow(modifier = Modifier.fillMaxWidth().background(SettingsActionBackground)) {
                SettingsActionButton(label = "Close", onClick = onDismissRequest)
            }
        }
    }
    when (selectedPage) {
        SettingsPage.General -> ApplicationPreferencesDropdownMenus(viewModel.global.applicationPreferences, preferencesDropdowns)
        SettingsPage.ContextSources -> ContextSourceSettingsDialogs(viewModel.global.contextSourceSettings)
        SettingsPage.OpenAi -> {
            AuthenticationSettingsOverlays(viewModel.global.authenticationSettings, authenticationDropdown)
            UsageResetDialogHost(viewModel.global.usageReset)
        }
        SettingsPage.NewSession -> {
            NewSessionDefaultsDropdownMenus(viewModel.newSession, defaultsDropdowns)
            SessionTitleSettingsDropdownMenus(viewModel.global.sessionTitleSettings, titleDropdowns)
        }
        SettingsPage.CurrentSession -> SessionSettingsOverlays(viewModel.session, sessionDropdowns)
        SettingsPage.Mcp, SettingsPage.Hooks -> Unit
    }
    McpSettingsDialogs(viewModel.global.mcpSettings)
    HookSettingsDialogs(viewModel.global.hookSettings)
}

@Composable
internal fun SettingsOperationFailureBanner(failed: Boolean, onDismiss: () -> Unit) {
    if (!failed) return
    Column(modifier = Modifier.fillMaxWidth().background(SettingsActionBackground)) {
        Text(
            "Could not confirm a Settings operation. Check current values before retrying.",
            color = SettingsErrorForeground,
        )
        SettingsActionButton("Dismiss", onClick = onDismiss)
    }
}

@Composable
internal fun SettingsPageViewport(width: Int, scrollState: ScrollState, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.width(width).fillMaxHeight().background(SettingsHomeBackground)
            .verticalScroll(scrollState),
    ) { content() }
}

internal fun SettingsPage.settingsLabel(): String = when (this) {
    SettingsPage.General -> "General"
    SettingsPage.ContextSources -> "Context sources"
    SettingsPage.OpenAi -> "OpenAI"
    SettingsPage.Mcp -> "MCP"
    SettingsPage.Hooks -> "Hooks"
    SettingsPage.CurrentSession -> "Current session"
    SettingsPage.NewSession -> "New session"
}

private const val SettingsMaximumWidth: Int = 84
private const val SettingsNavigationWidth: Int = 18
