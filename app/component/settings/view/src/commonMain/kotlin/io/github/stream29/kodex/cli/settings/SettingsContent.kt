package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.cli.accountusage.AccountUsageComponent
import io.github.stream29.kodex.cli.authenticationsettings.AuthenticationLogoutHost
import io.github.stream29.kodex.cli.authenticationsettings.AuthenticationSettingsPage
import io.github.stream29.kodex.cli.newsessiondefaults.NewSessionDefaultsContent
import io.github.stream29.kodex.cli.sessionsettings.SessionSettingsChildren
import io.github.stream29.kodex.cli.sessionsettings.SessionSettingsContent
import io.github.stream29.kodex.cli.usagereset.UsageResetContent

@Composable
public actual fun SettingsContent(
    viewModel: SettingsViewModel, renderer: SettingsRootRenderer,
    onDismissRequest: () -> Unit, onOpenLogin: () -> Unit,
    capabilities: SettingsHostCapabilities,
) {
    key(viewModel) {
        val selectedPage = viewModel.selectedPage.collectAsState().value
        val failed = viewModel.global.operationFailure.collectAsState().value
        val currentOpenLogin by rememberUpdatedState(onOpenLogin)
        val loginAvailable by rememberUpdatedState(capabilities.providerLogin)
        LaunchedEffect(viewModel.global) {
            viewModel.global.effects.collect { effect ->
                when (effect) { GlobalSettingsEffect.OpenLogin -> if (loginAvailable) currentOpenLogin() }
            }
        }
        renderer.PreparePage(selectedPage)
        val fields = renderer.fields
        renderer.Frame(
            selectedPage, SettingsPage.entries.map { SettingsNavigationEntry(it, it.settingsLabel()) },
            viewModel::selectPage, onDismissRequest,
            banner = { if (failed) renderer.FailureBanner(viewModel.global::dismissOperationFailure) },
        ) {
            when (selectedPage) {
                SettingsPage.General -> ApplicationPreferencesContent(viewModel.global.applicationPreferences, fields.preferences, false)
                SettingsPage.ContextSources -> if (capabilities.contextPathEditing) {
                    ContextSourceSettingsContent(viewModel.global.contextSourceSettings, fields.preferences, false)
                } else UiText("Context source path editing is unavailable in this host.")
                SettingsPage.OpenAi -> fields.authentication.Section("Account") {
                    AuthenticationSettingsPage(viewModel.global.authenticationSettings, fields.authentication, false, capabilities.providerLogin)
                    AccountUsageComponent(viewModel.global.accountUsage, false)
                }
                SettingsPage.Mcp -> McpSettingsPage(viewModel.global.mcpSettings, false)
                SettingsPage.Hooks -> {
                    UiText("Backend notifications")
                    HookSettingsPage(viewModel.global.hookSettings, false)
                    UiText(renderer.frontendErrorHooksLabel)
                    if (renderer.frontendErrorHooksAvailable) HookSettingsPage(viewModel.global.frontendErrorHooks, false)
                    else UiText("Frontend shell hooks are unavailable in this host.")
                }
                SettingsPage.CurrentSession -> SessionSettingsContent(viewModel.session, fields.session, capabilities.directoryBrowsing)
                SettingsPage.NewSession -> {
                    NewSessionDefaultsContent(viewModel.newSession, fields.defaults, false)
                    SessionTitleSettingsContent(viewModel.global.sessionTitleSettings, fields.title, false)
                }
            }
        }
        when (selectedPage) {
            SettingsPage.General -> renderer.Menus(SettingsFieldGroup.Preferences)
            SettingsPage.ContextSources -> if (capabilities.contextPathEditing) ContextSourceSettingsDialogHost(viewModel.global.contextSourceSettings) { title, dismiss, content ->
                renderer.Modal(SettingsModalFrame.ContextSource, title, dismiss, content)
            }
            SettingsPage.OpenAi -> {
                renderer.Menus(SettingsFieldGroup.Authentication)
                AuthenticationLogoutHost(viewModel.global.authenticationSettings) { title, dismiss, content ->
                    renderer.Modal(SettingsModalFrame.Logout, title, dismiss, content)
                }
                UsageResetContent(viewModel.global.usageReset) { title, dismiss, content ->
                    renderer.Modal(SettingsModalFrame.UsageReset, title, dismiss, content)
                }
            }
            SettingsPage.CurrentSession -> {
                renderer.Menus(SettingsFieldGroup.Session)
                SessionSettingsChildren(
                    viewModel.session,
                    rename = { handle, dismiss -> renderer.Rename(handle.viewModel, dismiss) },
                    directory = { handle, dismiss -> renderer.Directory(handle.selection, dismiss) },
                )
            }
            SettingsPage.NewSession -> {
                renderer.Menus(SettingsFieldGroup.Defaults)
                renderer.Menus(SettingsFieldGroup.Title)
            }
            SettingsPage.Mcp, SettingsPage.Hooks -> Unit
        }
        McpSettingsDialogHost(viewModel.global.mcpSettings, renderer.mcp)
        HookSettingsDialogHost(viewModel.global.hookSettings, renderer.hooks)
        if (renderer.frontendErrorHooksAvailable) HookSettingsDialogHost(viewModel.global.frontendErrorHooks, renderer.hooks)
    }
}

public actual fun SettingsPage.settingsLabel(): String = when (this) {
    SettingsPage.General -> "General"
    SettingsPage.ContextSources -> "Context sources"
    SettingsPage.OpenAi -> "OpenAI"
    SettingsPage.Mcp -> "MCP"
    SettingsPage.Hooks -> "Hooks"
    SettingsPage.CurrentSession -> "Current session"
    SettingsPage.NewSession -> "New session"
}
