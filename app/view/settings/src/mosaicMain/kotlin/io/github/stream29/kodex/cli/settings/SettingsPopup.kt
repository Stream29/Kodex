package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxHeight
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsEffect
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsState
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.SessionSettingsEffect
import io.github.stream29.kodex.app.settings.contract.SessionSettingsSnapshot
import io.github.stream29.kodex.app.settings.contract.SessionSettingsState
import io.github.stream29.kodex.app.settings.contract.SessionSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.app.settings.contract.SettingsViewModel
import io.github.stream29.kodex.cli.pathpicker.DirectoryPickerPopup
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.ScrollState
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiDropdownTrigger
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.components.verticalScroll
import io.github.stream29.kodex.hook.contract.HookImportDecision
import io.github.stream29.kodex.hook.contract.HookManagedSourceState
import io.github.stream29.kodex.mcp.contract.McpImportDecision
import io.github.stream29.kodex.openai.AgentMode
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.utils.externalurl.OpenExternalUrlResult
import io.github.stream29.kodex.utils.externalurl.openExternalUrl

/**
 * Direct renderer for one Settings ViewModel and its three stable page children.
 *
 * Dropdowns and rename input remain frontend-local. Business writes and reset
 * workflows are owned by the corresponding child ViewModel.
 */
@Composable
public fun BoxScope.SettingsPopup(
    viewModel: SettingsViewModel,
    onDismissRequest: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    DisposableEffect(viewModel) {
        onDispose(viewModel::close)
    }
    val selectedPage by viewModel.selectedPage.collectAsState()
    val dropdowns = SettingsDropdownStates(
        model = rememberTuiDropdownState(),
        reasoning = rememberTuiDropdownState(),
        serviceTier = rememberTuiDropdownState(),
        agentMode = rememberTuiDropdownState(),
        requestUserInputMode = rememberTuiDropdownState(),
    )
    var renameRequest by remember(viewModel) {
        mutableStateOf<SessionSettingsEffect.RenameSession?>(null)
    }
    var mcpEditorRequest by remember(viewModel) {
        mutableStateOf<McpEditorRequest?>(null)
    }
    var mcpDeleteRequest by remember(viewModel) {
        mutableStateOf<io.github.stream29.kodex.app.settings.contract.McpServerSettingsState?>(null)
    }
    var mcpDetailsServerName by remember(viewModel) { mutableStateOf<String?>(null) }
    var mcpImportOpen by remember(viewModel) { mutableStateOf(false) }
    var hookEditorRequest by remember(viewModel) {
        mutableStateOf<HookEditorRequest?>(null)
    }
    var hookDeleteRequest by remember(viewModel) {
        mutableStateOf<HookManagedSourceState?>(null)
    }
    var hookImportOpen by remember(viewModel) { mutableStateOf(false) }
    val currentOpenLogin by rememberUpdatedState(onOpenLogin)

    LaunchedEffect(viewModel.global) {
        viewModel.global.effects.collect { effect ->
            when (effect) {
                GlobalSettingsEffect.OpenLogin -> currentOpenLogin()
                is GlobalSettingsEffect.OpenMcpAuthorizationUrl -> {
                    if (openExternalUrl(effect.url) is OpenExternalUrlResult.Failed) {
                        viewModel.global.cancelMcpServerLogin(effect.serverName)
                    }
                }
            }
        }
    }
    LaunchedEffect(viewModel.session) {
        viewModel.session.effects.collect { effect ->
            when (effect) {
                is SessionSettingsEffect.RenameSession -> renameRequest = effect
            }
        }
    }
    LaunchedEffect(selectedPage) {
        renameRequest = null
        mcpEditorRequest = null
        mcpDeleteRequest = null
        mcpDetailsServerName = null
        hookEditorRequest = null
        hookDeleteRequest = null
        if (selectedPage != SettingsPage.Global) {
            mcpImportOpen = false
            viewModel.global.dismissCodexMcpImport()
            hookImportOpen = false
            viewModel.global.dismissCodexHookImport()
        }
    }

    val terminalSize = LocalTerminalState.current.size
    val width = (terminalSize.columns - 4).coerceIn(1, SettingsMaximumWidth)
    val height = (terminalSize.rows - 4).coerceAtLeast(1)
    val pageScrollState = remember(selectedPage) { ScrollState() }
    val navigationWidth = SettingsNavigationWidth.coerceAtMost((width - 1).coerceAtLeast(1))
    val contentWidth = (width - navigationWidth).coerceAtLeast(1)
    TuiDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(width).height(height).background(SettingsHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                value = "Settings",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TextStyle.Bold,
            )
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    modifier = Modifier
                        .width(navigationWidth)
                        .fillMaxHeight()
                        .background(SettingsNavigationBackground),
                ) {
                    SettingsPage.entries.forEach { candidate ->
                        val background = if (candidate == selectedPage) {
                            SettingsSelectionBackground
                        } else {
                            SettingsNavigationBackground
                        }
                        TuiButton(
                            label = candidate.settingsLabel(),
                            modifier = Modifier.fillMaxWidth().background(background),
                            color = SettingsForeground,
                            onClick = {
                                dropdowns.dismissAll()
                                viewModel.selectPage(candidate)
                            },
                        )
                    }
                }
                SettingsPageViewport(
                    width = contentWidth,
                    scrollState = pageScrollState,
                ) {
                    SettingsPageContent(
                        viewModel = viewModel,
                        page = selectedPage,
                        dropdowns = dropdowns,
                        onAddMcp = { mcpEditorRequest = McpEditorRequest() },
                        onOpenMcp = { server -> mcpDetailsServerName = server.serverName },
                        onImportMcp = {
                            viewModel.global.dismissCodexMcpImport()
                            mcpImportOpen = true
                            viewModel.global.previewCodexMcpImport()
                        },
                        onAddHook = { hookEditorRequest = HookEditorRequest() },
                        onEditHook = { source ->
                            viewModel.global.hookSourceEditorDraft(source.sourceId)?.let { draft ->
                                hookEditorRequest = HookEditorRequest(
                                    sourceId = source.sourceId,
                                    draft = draft,
                                )
                            }
                        },
                        onDeleteHook = { source -> hookDeleteRequest = source },
                        onImportHook = { hookImportOpen = true },
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth().background(SettingsActionBackground)) {
                TuiButton(
                    label = "Close",
                    color = SettingsForeground,
                    onClick = onDismissRequest,
                )
            }
        }
    }

    SettingsDropdownMenus(
        viewModel = viewModel,
        page = selectedPage,
        dropdowns = dropdowns,
    )
    if (selectedPage == SettingsPage.Global) {
        UsageResetDialogHost(viewModel.global)
    }
    renameRequest?.let { request ->
        RenameSessionDialog(
            request = request,
            onDismiss = { renameRequest = null },
            onRename = { name ->
                renameRequest = null
                viewModel.session.renameSession(request.expectedRevision, name)
            },
        )
    }
    mcpDetailsServerName?.let { serverName ->
        val servers by viewModel.global.mcpServers.collectAsState()
        servers.firstOrNull { server -> server.serverName == serverName }?.let { server ->
            McpServerDetailsDialog(
                server = server,
                onDismiss = { mcpDetailsServerName = null },
                onEdit = {
                    mcpDetailsServerName = null
                    mcpEditorRequest = McpEditorRequest(existing = server)
                },
                onDelete = {
                    mcpDetailsServerName = null
                    mcpDeleteRequest = server
                },
                onSetEnabled = {
                    viewModel.global.setMcpServerEnabled(server.serverName, !server.enabled)
                },
                onLogin = { viewModel.global.loginMcpServer(server.serverName) },
                onCancelLogin = { viewModel.global.cancelMcpServerLogin(server.serverName) },
                onLogout = { viewModel.global.logoutMcpServer(server.serverName) },
                onReconnect = { viewModel.global.reconnectMcpServer(server.serverName) },
            )
        }
    }
    mcpEditorRequest?.let { request ->
        McpServerEditorDialog(
            request = request,
            onDismiss = { mcpEditorRequest = null },
            onSave = { draft ->
                request.existing?.let { existing ->
                    viewModel.global.editMcpServer(existing.serverName, draft)
                } ?: viewModel.global.addMcpServer(draft)
                mcpEditorRequest = null
            },
        )
    }
    mcpDeleteRequest?.let { server ->
        McpDeleteConfirmationDialog(
            server = server,
            onDismiss = { mcpDeleteRequest = null },
            onConfirm = {
                viewModel.global.deleteMcpServer(server.serverName)
                mcpDeleteRequest = null
            },
        )
    }
    if (mcpImportOpen) {
        val preview by viewModel.global.mcpImportPreview.collectAsState()
        McpImportDialog(
            preview = preview,
            onApply = { previewId: Long, decisions: Map<String, McpImportDecision> ->
                viewModel.global.applyCodexMcpImport(previewId, decisions)
                mcpImportOpen = false
            },
            onDismiss = {
                viewModel.global.dismissCodexMcpImport()
                mcpImportOpen = false
            },
        )
    }
    hookEditorRequest?.let { request ->
        HookSourceEditorDialog(
            request = request,
            onDismiss = { hookEditorRequest = null },
            onSave = { draft ->
                request.sourceId?.let { sourceId ->
                    viewModel.global.editHookSource(sourceId, draft)
                } ?: viewModel.global.addHookSource(draft)
                hookEditorRequest = null
            },
        )
    }
    hookDeleteRequest?.let { source ->
        HookDeleteConfirmationDialog(
            source = source,
            onDismiss = { hookDeleteRequest = null },
            onConfirm = {
                viewModel.global.deleteHookSource(source.sourceId)
                hookDeleteRequest = null
            },
        )
    }
    if (hookImportOpen) {
        val preview by viewModel.global.hookImportPreview.collectAsState()
        HookImportDialog(
            preview = preview,
            onPreview = viewModel.global::previewCodexHookImport,
            onApply = { previewId: Long, decisions: Map<String, HookImportDecision> ->
                viewModel.global.applyCodexHookImport(previewId, decisions)
                hookImportOpen = false
            },
            onDismiss = {
                viewModel.global.dismissCodexHookImport()
                hookImportOpen = false
            },
        )
    }
    val directoryPicker by viewModel.session.directoryPicker.collectAsState()
    directoryPicker?.let { picker ->
        DirectoryPickerPopup(
            viewModel = picker.viewModel,
            onDismissRequest = {
                viewModel.session.dismissWorkingDirectoryPicker(picker)
            },
            onDirectorySelected = { directory ->
                viewModel.session.selectWorkingDirectory(picker, directory)
            },
        )
    }
}

@Composable
internal fun SettingsPageViewport(
    width: Int,
    scrollState: ScrollState,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(SettingsHomeBackground)
            .verticalScroll(scrollState),
    ) {
        content()
    }
}

@Composable
private fun SettingsPageContent(
    viewModel: SettingsViewModel,
    page: SettingsPage,
    dropdowns: SettingsDropdownStates,
    onAddMcp: () -> Unit,
    onOpenMcp: (io.github.stream29.kodex.app.settings.contract.McpServerSettingsState) -> Unit,
    onImportMcp: () -> Unit,
    onAddHook: () -> Unit,
    onEditHook: (HookManagedSourceState) -> Unit,
    onDeleteHook: (HookManagedSourceState) -> Unit,
    onImportHook: () -> Unit,
) {
    when (page) {
        SettingsPage.Global -> GlobalSettingsContent(
            viewModel = viewModel.global,
            dropdowns = dropdowns,
            onAddMcp = onAddMcp,
            onOpenMcp = onOpenMcp,
            onImportMcp = onImportMcp,
            onAddHook = onAddHook,
            onEditHook = onEditHook,
            onDeleteHook = onDeleteHook,
            onImportHook = onImportHook,
        )

        SettingsPage.Session -> SessionSettingsContent(viewModel.session, dropdowns)
        SettingsPage.NewSession -> NewSessionSettingsContent(viewModel.newSession, dropdowns)
    }
}

@Composable
private fun GlobalSettingsContent(
    viewModel: GlobalSettingsViewModel,
    dropdowns: SettingsDropdownStates,
    onAddMcp: () -> Unit,
    onOpenMcp: (io.github.stream29.kodex.app.settings.contract.McpServerSettingsState) -> Unit,
    onImportMcp: () -> Unit,
    onAddHook: () -> Unit,
    onEditHook: (HookManagedSourceState) -> Unit,
    onDeleteHook: (HookManagedSourceState) -> Unit,
    onImportHook: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val authentication by viewModel.authentication.collectAsState()
    val accountUsage by viewModel.accountUsage.collectAsState()
    val mcpServers by viewModel.mcpServers.collectAsState()
    val hooksEnabled by viewModel.hooksEnabled.collectAsState()
    val hookSources by viewModel.hookSources.collectAsState()

    Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
        Text("Codex source (auth and imports)", color = SettingsForeground)
        Text(
            state.codexHome.toString(),
            modifier = Modifier.fillMaxWidth(),
            color = SettingsForeground,
        )
    }
    McpSettingsContent(
        servers = mcpServers,
        onAdd = onAddMcp,
        onOpenDetails = onOpenMcp,
        onImport = onImportMcp,
    )
    HookSettingsContent(
        featureEnabled = hooksEnabled,
        sources = hookSources,
        onSetFeatureEnabled = viewModel::setHooksEnabled,
        onAdd = onAddHook,
        onEdit = onEditHook,
        onDelete = onDeleteHook,
        onSetEnabled = viewModel::setHookSourceEnabled,
        onImport = onImportHook,
    )
    SettingsChoiceGroup(
        label = "Authentication",
        options = KodexAuthSource.entries.toList(),
        selected = state.authSource,
        optionLabel = KodexAuthSource::dialogLabel,
        background = SettingsHomeBackground,
        enabled = true,
        onSelect = viewModel::updateAuthSource,
    )
    SettingsChoiceGroup(
        label = "Automatic session title",
        options = listOf(true, false),
        selected = state.sessionTitle.enabled,
        optionLabel = { enabled -> if (enabled) "Enabled" else "Disabled" },
        background = SettingsHomeBackground,
        enabled = true,
        onSelect = viewModel::updateSessionTitleEnabled,
    )
    SettingsDropdownField(
        label = "Title model",
        selectedLabel = state.effectiveSessionTitleModel.value,
        dropdownState = dropdowns.model,
        background = SettingsNewLineBackground,
    )
    SettingsDropdownField(
        label = "Title reasoning",
        selectedLabel = state.sessionTitle.reasoningEffort.displayName(),
        dropdownState = dropdowns.reasoning,
        background = SettingsSubmitKeyBackground,
    )
    AuthenticationSettingsContent(
        authState = authentication,
        onOpenLogin = viewModel::requestLogin,
    )
    CodexAccountUsageSettingsContent(
        state = accountUsage,
        onRefresh = viewModel::refreshUsage,
        onUseReset = viewModel::requestUsageReset,
    )
    Row(modifier = Modifier.fillMaxWidth().background(SettingsNewLineBackground)) {
        Column(modifier = Modifier.weight(1f).background(SettingsNewLineBackground)) {
            Text("New line key", color = SettingsForeground)
            Row {
                NewLineKey.entries.forEachIndexed { index, key ->
                    if (index != 0) Text(" ")
                    TuiButton(
                        label = key.dialogLabel(),
                        modifier = Modifier.background(
                            if (key == state.newLineKey) {
                                SettingsSelectionBackground
                            } else {
                                SettingsNewLineBackground
                            },
                        ),
                        color = SettingsForeground,
                        onClick = { viewModel.updateNewLineKey(key) },
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f).background(SettingsSubmitKeyBackground)) {
            Text("Submit key", color = SettingsForeground)
            Row {
                SubmitKey.entries.forEachIndexed { index, key ->
                    if (index != 0) Text(" ")
                    TuiButton(
                        label = key.dialogLabel(),
                        modifier = Modifier.background(
                            if (key == state.newLineKey.submitKey) {
                                SettingsSelectionBackground
                            } else {
                                SettingsSubmitKeyBackground
                            },
                        ),
                        color = SettingsForeground,
                        onClick = { viewModel.updateNewLineKey(key.newLineKey) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun AuthenticationSettingsContent(
    authState: SettingsAuthenticationState,
    onOpenLogin: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
        Text("OpenAI account", color = SettingsForeground)
        when (authState) {
            is SettingsAuthenticationState.Authenticated -> {
                val identity = authState.email
                    ?.let { email -> "Signed in as $email" }
                    ?: authState.accountId
                        ?.let { accountId -> "Signed in as account $accountId" }
                    ?: "Signed in"
                Text(identity, modifier = Modifier.fillMaxWidth(), color = SettingsForeground)
                authState.planType?.let { plan ->
                    Text(
                        value = "Plan: ${plan.rawValue}",
                        modifier = Modifier.fillMaxWidth(),
                        color = SettingsForeground,
                    )
                }
            }

            is SettingsAuthenticationState.Unavailable -> {
                Text("Authentication unavailable", color = SettingsForeground)
                Text(
                    value = authState.reason.settingsDescription(),
                    modifier = Modifier.fillMaxWidth(),
                    color = SettingsForeground,
                )
                TuiButton(
                    label = "Sign in",
                    modifier = Modifier.background(SettingsHomeBackground),
                    color = SettingsForeground,
                    onClick = onOpenLogin,
                )
            }
        }
    }
}

@Composable
private fun SessionSettingsContent(
    viewModel: SessionSettingsViewModel,
    dropdowns: SettingsDropdownStates,
) {
    val state by viewModel.state.collectAsState()
    when (val current = state) {
        SessionSettingsState.Unavailable -> Text(
            value = "No selected session",
            modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground),
            color = SettingsForeground,
        )

        is SessionSettingsState.Available -> {
            val snapshot = current.snapshot
            SessionNameSettingsContent(
                snapshot = snapshot,
                onRename = { viewModel.requestRename(snapshot.revision) },
            )
            WorkingDirectorySettingsContent(
                snapshot = snapshot,
                onBrowse = { viewModel.requestWorkingDirectory(snapshot.revision) },
            )
            ConfigurationSettingsContent(snapshot, dropdowns)
        }
    }
}

@Composable
private fun SessionNameSettingsContent(
    snapshot: SessionSettingsSnapshot,
    onRename: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
        Text("Session name", color = SettingsForeground)
        Text(
            value = snapshot.sessionName,
            modifier = Modifier.fillMaxWidth(),
            color = SettingsForeground,
        )
        TuiButton(
            label = "Rename",
            modifier = Modifier.background(SettingsHomeBackground),
            color = SettingsForeground,
            onClick = onRename,
        )
    }
}

@Composable
private fun WorkingDirectorySettingsContent(
    snapshot: SessionSettingsSnapshot,
    onBrowse: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
        Text("Working directory", color = SettingsForeground)
        Text(
            value = snapshot.configuration.workingDirectory.toString(),
            modifier = Modifier.fillMaxWidth(),
            color = SettingsForeground,
        )
        TuiButton(
            label = "Browse",
            modifier = Modifier.background(SettingsHomeBackground),
            color = SettingsForeground,
            enabled = snapshot.editable,
            onClick = onBrowse,
        )
    }
}

@Composable
private fun ConfigurationSettingsContent(
    snapshot: SessionSettingsSnapshot,
    dropdowns: SettingsDropdownStates,
) {
    val configuration = snapshot.configuration
    SettingsDropdownField(
        label = "Model",
        selectedLabel = configuration.model.value,
        dropdownState = dropdowns.model,
        background = SettingsHomeBackground,
        enabled = snapshot.editable,
    )
    SettingsDropdownField(
        label = "Reasoning",
        selectedLabel = configuration.reasoningEffort.displayName(),
        dropdownState = dropdowns.reasoning,
        background = SettingsNewLineBackground,
        enabled = snapshot.editable,
    )
    SettingsDropdownField(
        label = "Service tier",
        selectedLabel = configuration.serviceTier.displayName(),
        dropdownState = dropdowns.serviceTier,
        background = SettingsSubmitKeyBackground,
        enabled = snapshot.editable,
    )
    SettingsDropdownField(
        label = "Agent mode",
        selectedLabel = configuration.agentMode.displayName(),
        dropdownState = dropdowns.agentMode,
        background = SettingsAgentModeBackground,
        enabled = snapshot.editable,
    )
    SettingsDropdownField(
        label = "Questions",
        selectedLabel = configuration.requestUserInputMode.displayName(),
        dropdownState = dropdowns.requestUserInputMode,
        background = SettingsQuestionModeBackground,
        enabled = snapshot.editable,
    )
}

@Composable
private fun NewSessionSettingsContent(
    viewModel: NewSessionSettingsViewModel,
    dropdowns: SettingsDropdownStates,
) {
    val state by viewModel.state.collectAsState()
    NewSessionConfigurationContent(state, dropdowns)
}

@Composable
private fun NewSessionConfigurationContent(
    state: NewSessionSettingsState,
    dropdowns: SettingsDropdownStates,
) {
    SettingsDropdownField(
        label = "Model",
        selectedLabel = state.settings.model.value,
        dropdownState = dropdowns.model,
        background = SettingsHomeBackground,
    )
    SettingsDropdownField(
        label = "Reasoning",
        selectedLabel = state.settings.reasoningEffort.displayName(),
        dropdownState = dropdowns.reasoning,
        background = SettingsNewLineBackground,
    )
    SettingsDropdownField(
        label = "Service tier",
        selectedLabel = state.settings.serviceTier.displayName(),
        dropdownState = dropdowns.serviceTier,
        background = SettingsSubmitKeyBackground,
    )
    SettingsDropdownField(
        label = "Agent mode",
        selectedLabel = state.settings.agentMode.displayName(),
        dropdownState = dropdowns.agentMode,
        background = SettingsAgentModeBackground,
    )
    SettingsDropdownField(
        label = "Questions",
        selectedLabel = state.settings.requestUserInputMode.displayName(),
        dropdownState = dropdowns.requestUserInputMode,
        background = SettingsQuestionModeBackground,
    )
}

@Composable
private fun SettingsDropdownField(
    label: String,
    selectedLabel: String,
    dropdownState: TuiDropdownState,
    background: Color,
    enabled: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth().background(background)) {
        Text(label, color = SettingsForeground)
        TuiDropdownTrigger(
            dropdownState = dropdownState,
            label = selectedLabel,
            modifier = Modifier.fillMaxWidth().background(background),
            color = SettingsForeground,
            enabled = enabled,
        )
    }
}

@Composable
private fun BoxScope.SettingsDropdownMenus(
    viewModel: SettingsViewModel,
    page: SettingsPage,
    dropdowns: SettingsDropdownStates,
) {
    when (page) {
        SettingsPage.Global -> GlobalSettingsDropdownMenus(viewModel.global, dropdowns)
        SettingsPage.Session -> SessionSettingsDropdownMenus(viewModel.session, dropdowns)
        SettingsPage.NewSession -> NewSessionSettingsDropdownMenus(viewModel.newSession, dropdowns)
    }
}

@Composable
private fun BoxScope.GlobalSettingsDropdownMenus(
    viewModel: GlobalSettingsViewModel,
    dropdowns: SettingsDropdownStates,
) {
    val state by viewModel.state.collectAsState()
    TuiDropdownMenu(
        dropdownState = dropdowns.model,
        options = state.modelOptions,
        selected = state.effectiveSessionTitleModel,
        optionLabel = OpenAiModelId::value,
        backgroundColor = PopupMenuBackground,
        onSelect = viewModel::updateSessionTitleModel,
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.reasoning,
        options = knownReasoningEfforts,
        selected = state.sessionTitle.reasoningEffort,
        optionLabel = ReasoningEffort::displayName,
        backgroundColor = PopupMenuBackground,
        onSelect = viewModel::updateSessionTitleReasoningEffort,
    )
}

@Composable
private fun BoxScope.SessionSettingsDropdownMenus(
    viewModel: SessionSettingsViewModel,
    dropdowns: SettingsDropdownStates,
) {
    val state by viewModel.state.collectAsState()
    val available = state as? SessionSettingsState.Available ?: return
    val snapshot = available.snapshot
    val configuration = snapshot.configuration
    TuiDropdownMenu(
        dropdownState = dropdowns.model,
        options = available.modelOptions,
        selected = configuration.model,
        optionLabel = OpenAiModelId::value,
        enabled = snapshot.editable,
        backgroundColor = PopupMenuBackground,
        onSelect = { model -> viewModel.updateModel(snapshot.revision, model) },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.reasoning,
        options = knownReasoningEfforts,
        selected = configuration.reasoningEffort,
        optionLabel = ReasoningEffort::displayName,
        enabled = snapshot.editable,
        backgroundColor = PopupMenuBackground,
        onSelect = { effort -> viewModel.updateReasoningEffort(snapshot.revision, effort) },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.serviceTier,
        options = ServiceTier.entries.toList(),
        selected = configuration.serviceTier,
        optionLabel = ServiceTier::displayName,
        enabled = snapshot.editable,
        backgroundColor = PopupMenuBackground,
        onSelect = { tier -> viewModel.updateServiceTier(snapshot.revision, tier) },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.agentMode,
        options = AgentMode.entries.toList(),
        selected = configuration.agentMode,
        optionLabel = AgentMode::displayName,
        enabled = snapshot.editable,
        backgroundColor = PopupMenuBackground,
        onSelect = { agentMode -> viewModel.updateAgentMode(snapshot.revision, agentMode) },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.requestUserInputMode,
        options = RequestUserInputMode.entries.toList(),
        selected = configuration.requestUserInputMode,
        optionLabel = RequestUserInputMode::displayName,
        enabled = snapshot.editable,
        backgroundColor = PopupMenuBackground,
        onSelect = { mode ->
            viewModel.updateRequestUserInputMode(snapshot.revision, mode)
        },
    )
}

@Composable
private fun BoxScope.NewSessionSettingsDropdownMenus(
    viewModel: NewSessionSettingsViewModel,
    dropdowns: SettingsDropdownStates,
) {
    val state by viewModel.state.collectAsState()
    TuiDropdownMenu(
        dropdownState = dropdowns.model,
        options = state.modelOptions,
        selected = state.settings.model,
        optionLabel = OpenAiModelId::value,
        backgroundColor = PopupMenuBackground,
        onSelect = { model -> viewModel.updateModel(state.revision, model) },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.reasoning,
        options = knownReasoningEfforts,
        selected = state.settings.reasoningEffort,
        optionLabel = ReasoningEffort::displayName,
        backgroundColor = PopupMenuBackground,
        onSelect = { effort -> viewModel.updateReasoningEffort(state.revision, effort) },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.serviceTier,
        options = ServiceTier.entries.toList(),
        selected = state.settings.serviceTier,
        optionLabel = ServiceTier::displayName,
        backgroundColor = PopupMenuBackground,
        onSelect = { tier -> viewModel.updateServiceTier(state.revision, tier) },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.agentMode,
        options = AgentMode.entries.toList(),
        selected = state.settings.agentMode,
        optionLabel = AgentMode::displayName,
        backgroundColor = PopupMenuBackground,
        onSelect = { agentMode -> viewModel.updateAgentMode(state.revision, agentMode) },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.requestUserInputMode,
        options = RequestUserInputMode.entries.toList(),
        selected = state.settings.requestUserInputMode,
        optionLabel = RequestUserInputMode::displayName,
        backgroundColor = PopupMenuBackground,
        onSelect = { mode -> viewModel.updateRequestUserInputMode(state.revision, mode) },
    )
}

@Composable
private fun BoxScope.RenameSessionDialog(
    request: SessionSettingsEffect.RenameSession,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, RenameMaximumWidth)
    val input = remember(request) {
        TextInputState(
            TextInputValue(
                text = request.initialName,
                cursorOffset = request.initialName.length,
            ),
        )
    }
    val layout = TextInputLayout.create(value = input.value, width = width)
    fun confirm() {
        input.value.text.trim().takeIf(String::isNotEmpty)?.let(onRename)
    }

    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
            Text(
                value = "Rename session",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TextStyle.Bold,
            )
            Text("Session name", color = SettingsForeground)
            TextInput(
                state = input,
                layout = layout,
                modifier = Modifier.fillMaxWidth(),
                autoFocus = true,
                onKeyEvent = { event ->
                    if (event.key == "Enter" && !event.shift && !event.ctrl && !event.alt) {
                        confirm()
                        true
                    } else {
                        false
                    }
                },
            )
        }
    }
}

@Composable
private fun <T> SettingsChoiceGroup(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    background: Color,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(background)) {
        Text(label, color = SettingsForeground)
        Row {
            options.forEachIndexed { index, option ->
                if (index != 0) Text(" ")
                TuiButton(
                    label = optionLabel(option),
                    modifier = Modifier.background(
                        if (option == selected) SettingsSelectionBackground else background,
                    ),
                    color = SettingsForeground,
                    enabled = enabled,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

private class SettingsDropdownStates(
    val model: TuiDropdownState,
    val reasoning: TuiDropdownState,
    val serviceTier: TuiDropdownState,
    val agentMode: TuiDropdownState,
    val requestUserInputMode: TuiDropdownState,
) {
    fun dismissAll() {
        model.dismiss()
        reasoning.dismiss()
        serviceTier.dismiss()
        agentMode.dismiss()
        requestUserInputMode.dismiss()
    }
}

private fun SettingsPage.settingsLabel(): String = when (this) {
    SettingsPage.Global -> "Global"
    SettingsPage.Session -> "Session"
    SettingsPage.NewSession -> "New session"
}

private fun OpenAiAuthState.Unavailable.settingsDescription(): String =
    when (this) {
        OpenAiAuthState.Unavailable.NotLoaded ->
            "Authentication credentials have not been loaded yet."

        OpenAiAuthState.Unavailable.CredentialsNotFound ->
            "No credentials were found in the selected authentication source."

        OpenAiAuthState.Unavailable.UnsupportedAuthMode ->
            "The selected credentials use an unsupported authentication mode."

        OpenAiAuthState.Unavailable.InvalidCredentials ->
            "The selected credentials are malformed or incomplete."

        OpenAiAuthState.Unavailable.CredentialSourceUnavailable ->
            "The selected credential source could not be read."

        OpenAiAuthState.Unavailable.UnexpectedFailure ->
            "Authentication failed because of an unexpected internal error."
    }

private fun NewLineKey.dialogLabel(): String = when (this) {
    NewLineKey.ShiftEnter -> "Shift+Enter"
    NewLineKey.Enter -> "Enter"
}

private fun SubmitKey.dialogLabel(): String = when (this) {
    SubmitKey.Enter -> "Enter"
    SubmitKey.CtrlEnter -> "Ctrl+Enter"
}

private fun KodexAuthSource.dialogLabel(): String = when (this) {
    KodexAuthSource.Codex -> "Codex"
    KodexAuthSource.Kodex -> "Kodex"
}

private fun ReasoningEffort.displayName(): String = when (this) {
    ReasoningEffort.None -> "none"
    ReasoningEffort.Minimal -> "minimal"
    ReasoningEffort.Low -> "low"
    ReasoningEffort.Medium -> "medium"
    ReasoningEffort.High -> "high"
    ReasoningEffort.XHigh -> "xhigh"
    ReasoningEffort.Max -> "max"
    ReasoningEffort.Ultra -> "ultra"
    is ReasoningEffort.Custom -> wireName
}

private fun ServiceTier.displayName(): String = when (this) {
    ServiceTier.Default -> "default"
    ServiceTier.Fast -> "fast"
    ServiceTier.Flex -> "flex"
}

private fun AgentMode.displayName(): String = when (this) {
    AgentMode.Single -> "single agent"
    AgentMode.Multi -> "multi agent"
}

private fun RequestUserInputMode.displayName(): String = when (this) {
    RequestUserInputMode.AskUser -> "ask user"
    RequestUserInputMode.NoQuestion -> "no question"
}

private val knownReasoningEfforts: List<ReasoningEffort> = listOf(
    ReasoningEffort.None,
    ReasoningEffort.Minimal,
    ReasoningEffort.Low,
    ReasoningEffort.Medium,
    ReasoningEffort.High,
    ReasoningEffort.XHigh,
    ReasoningEffort.Max,
    ReasoningEffort.Ultra,
)

internal val SettingsForeground: Color = Color.White
internal val SettingsHeaderBackground: Color = Color(28, 68, 74)
internal val SettingsNavigationBackground: Color = Color(42, 42, 46)
internal val SettingsSelectionBackground: Color = Color(36, 78, 84)
internal val SettingsHomeBackground: Color = Color(52, 52, 56)
internal val SettingsNewLineBackground: Color = Color(62, 62, 66)
internal val SettingsSubmitKeyBackground: Color = Color(58, 58, 64)
internal val SettingsAgentModeBackground: Color = Color(46, 58, 62)
internal val SettingsQuestionModeBackground: Color = Color(42, 54, 58)
internal val SettingsActionBackground: Color = Color(28, 68, 74)
internal val PopupMenuBackground: Color = Color(42, 42, 46)

private const val SettingsMaximumWidth: Int = 84
private const val SettingsNavigationWidth: Int = 18
private const val RenameMaximumWidth: Int = 72
