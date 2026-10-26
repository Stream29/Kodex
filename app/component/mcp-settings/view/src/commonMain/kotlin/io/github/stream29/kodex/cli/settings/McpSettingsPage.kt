package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.mcpsettings.*
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsStatus
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.mcp.contract.*
import kotlinx.coroutines.CancellationException

@Composable
public actual fun McpSettingsPage(viewModel: McpSettingsViewModel, showOperationFailure: Boolean) {
    val state = viewModel.state.collectAsState().value
    if (state.closed) return
    key(viewModel) { UiColumn {
        if (showOperationFailure && state.operationFailure) {
            UiText("A settings operation failed.", UiTextRole.Error)
            McpAction("Dismiss", onClick = viewModel::dismissFailure)
        }
        McpSettingsContent(state.servers, viewModel::add, { viewModel.details(it.serverName) }, viewModel::importCodex)
    } }
}

@Composable
public actual fun McpSettingsContent(
    servers: List<McpServerSettingsState>, onAdd: () -> Unit, onOpenDetails: (McpServerSettingsState) -> Unit, onImport: () -> Unit,
) {
    McpListFrame {
        McpListHeader("MCP servers", "Add", "Import from Codex", onAdd, onImport)
        if (servers.isEmpty()) UiText("None configured", UiTextRole.Supporting)
        servers.forEach { server ->
            key(server.serverName) {
                McpListEntry("${server.serverName} · ${server.status.settingsLabel()}") { onOpenDetails(server) }
            }
        }
    }
}

@Composable
public actual fun McpSettingsDialogHost(viewModel: McpSettingsViewModel, renderer: McpDialogRenderer) {
    val state = viewModel.state.collectAsState().value
    if (state.closed) return
    when (val dialog = state.dialog) {
        McpSettingsDialog.Hidden -> Unit
        is McpSettingsDialog.Details -> key(viewModel, dialog.token) {
            renderer.Details(
                dialog.server, { viewModel.dismiss(dialog.token) }, { viewModel.edit(dialog.token) },
                { viewModel.requestDelete(dialog.token) }, { viewModel.setEnabled(dialog.token, !dialog.server.enabled) },
                { viewModel.login(dialog.token) }, { viewModel.cancelLogin(dialog.token) },
                { viewModel.logout(dialog.token) }, { viewModel.reconnect(dialog.token) },
            )
        }
        is McpSettingsDialog.Editing -> key(viewModel, dialog.token) {
            renderer.Editor(dialog, { viewModel.updateDraft(dialog.token, it) }, { viewModel.dismiss(dialog.token) }, { viewModel.save(dialog.token) })
        }
        is McpSettingsDialog.Deleting -> key(viewModel, dialog.token) {
            renderer.Deleting(dialog.server, dialog.error, { viewModel.dismiss(dialog.token) }, { viewModel.confirmDelete(dialog.token) })
        }
        is McpSettingsDialog.ImportLoading -> key(viewModel, dialog.token) {
            renderer.Import(null, emptyMap(), {}, {}, {}, {}, {}, { viewModel.dismiss(dialog.token) }, null, false, {})
        }
        is McpSettingsDialog.ImportFailed -> key(viewModel, dialog.token) {
            renderer.Import(null, emptyMap(), {}, {}, {}, {}, {}, { viewModel.dismiss(dialog.token) }, null, true, { viewModel.retryImport(dialog.token) })
        }
        is McpSettingsDialog.ImportPreview -> key(viewModel, dialog.token) {
            renderer.Import(
                dialog.preview, dialog.decisions,
                { viewModel.toggleImport(dialog.token, it) }, { viewModel.selectAllImports(dialog.token) },
                { viewModel.clearImports(dialog.token) }, { viewModel.filterImport(dialog.token, it) },
                { viewModel.applyImport(dialog.token) }, { viewModel.dismiss(dialog.token) }, dialog.error, false, {},
            )
        }
    }
}

@Composable
public actual fun McpSettingsEffects(viewModel: McpSettingsViewModel, openUrl: suspend (String) -> Boolean) {
    val currentOpen by rememberUpdatedState(openUrl)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is McpSettingsEffect.OpenAuthorizationUrl -> {
                    try {
                        if (!currentOpen(effect.url)) effect.cancel()
                    } catch (cancelled: CancellationException) {
                        effect.cancel()
                        throw cancelled
                    } catch (_: Throwable) {
                        effect.cancel()
                    }
                }
            }
        }
    }
}

internal fun McpServerSettingsStatus.settingsLabel(): String = when (this) {
    McpServerSettingsStatus.Disabled -> "Disabled"
    is McpServerSettingsStatus.AuthenticationBlocked -> "Authentication: ${state.settingsLabel()}"
    McpServerSettingsStatus.Connecting -> "Connecting"
    is McpServerSettingsStatus.Healthy -> "Healthy ($toolCount ${if (toolCount == 1) "tool" else "tools"})"
    is McpServerSettingsStatus.Failed -> "Failed: ${reason.settingsLabel()}"
    McpServerSettingsStatus.Closed -> "Closed"
}

internal fun McpAuthenticationState.settingsLabel(): String = when (this) {
    McpAuthenticationState.NotConfigured -> "Not configured"
    McpAuthenticationState.LoginRequired -> "Login required"
    McpAuthenticationState.ReauthorizationRequired -> "Login required again"
    McpAuthenticationState.Authorizing -> "Waiting for browser"
    McpAuthenticationState.Authorized -> "Authorized"
    McpAuthenticationState.Refreshing -> "Refreshing"
    is McpAuthenticationState.Failed -> message
}

internal fun McpTransportKind.settingsLabel(): String = when (this) {
    McpTransportKind.StreamableHttp -> "Streamable HTTP"
    McpTransportKind.Stdio -> "stdio"
}

private fun McpClientFailureReason.settingsLabel(): String = when (this) {
    McpClientFailureReason.Transport -> "Transport"
    McpClientFailureReason.Initialization -> "Initialization"
    McpClientFailureReason.ConnectionLost -> "Connection lost"
    McpClientFailureReason.ToolCatalog -> "Tool catalog"
}
