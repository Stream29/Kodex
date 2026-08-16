package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
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
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsEffect
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsState
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsState
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.SessionSettingsEffect
import io.github.stream29.kodex.app.settings.contract.SessionSettingsSnapshot
import io.github.stream29.kodex.app.settings.contract.SessionSettingsState
import io.github.stream29.kodex.app.settings.contract.SessionSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
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
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiDropdownTrigger
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.components.verticalScroll
import io.github.stream29.kodex.hook.contract.HookManagedState
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
        authentication = rememberTuiDropdownState(),
        automaticSessionTitle = rememberTuiDropdownState(),
        model = rememberTuiDropdownState(),
        reasoning = rememberTuiDropdownState(),
        serviceTier = rememberTuiDropdownState(),
        agentMode = rememberTuiDropdownState(),
        requestUserInputMode = rememberTuiDropdownState(),
        newLineKey = rememberTuiDropdownState(),
        submitKey = rememberTuiDropdownState(),
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
        mutableStateOf<HookManagedState?>(null)
    }
    var hookDetailsName by remember(viewModel) { mutableStateOf<String?>(null) }
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
        hookDetailsName = null
        if (selectedPage != SettingsPage.Global) {
            mcpImportOpen = false
            viewModel.global.dismissCodexMcpImport()
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
                textStyle = TuiTheme.typography.headline,
            )
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    modifier = Modifier
                        .width(navigationWidth)
                        .fillMaxHeight()
                        .background(SettingsNavigationBackground),
                ) {
                    SettingsPage.entries.forEach { candidate ->
                        TuiButton(
                            label = candidate.settingsLabel(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SettingsNavigationBackground),
                            color = SettingsForeground,
                            selected = candidate == selectedPage,
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
                        onOpenHook = { hook -> hookDetailsName = hook.name },
                    )
                }
            }
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
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
    hookDetailsName?.let { name ->
        val hooks by viewModel.global.hooks.collectAsState()
        hooks.firstOrNull { hook -> hook.name == name }?.let { hook ->
            HookDetailsDialog(
                hook = hook,
                onDismiss = { hookDetailsName = null },
                onEdit = {
                    viewModel.global.hookEditorDraft(hook.name)?.let { draft ->
                        hookDetailsName = null
                        hookEditorRequest = HookEditorRequest(
                            name = hook.name,
                            draft = draft,
                        )
                    }
                },
                onDelete = {
                    hookDetailsName = null
                    hookDeleteRequest = hook
                },
            )
        }
    }
    hookEditorRequest?.let { request ->
        HookEditorDialog(
            request = request,
            onDismiss = { hookEditorRequest = null },
            onSave = { draft ->
                request.name?.let { name ->
                    viewModel.global.editHook(name, draft)
                } ?: viewModel.global.addHook(draft)
                hookEditorRequest = null
            },
        )
    }
    hookDeleteRequest?.let { hook ->
        HookDeleteConfirmationDialog(
            hook = hook,
            onDismiss = { hookDeleteRequest = null },
            onConfirm = {
                viewModel.global.deleteHook(hook.name)
                hookDeleteRequest = null
            },
        )
    }
    val codexHomePicker by viewModel.global.codexHomePicker.collectAsState()
    codexHomePicker?.let { picker ->
        DirectoryPickerPopup(
            viewModel = picker.viewModel,
            onDismissRequest = {
                viewModel.global.dismissCodexHomePicker(picker)
            },
            onDirectorySelected = { directory ->
                viewModel.global.selectCodexHome(picker, directory)
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
    onOpenHook: (HookManagedState) -> Unit,
) {
    when (page) {
        SettingsPage.Global -> GlobalSettingsContent(
            viewModel = viewModel.global,
            dropdowns = dropdowns,
            onAddMcp = onAddMcp,
            onOpenMcp = onOpenMcp,
            onImportMcp = onImportMcp,
            onAddHook = onAddHook,
            onOpenHook = onOpenHook,
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
    onOpenHook: (HookManagedState) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val authentication by viewModel.authentication.collectAsState()
    val accountUsage by viewModel.accountUsage.collectAsState()
    val mcpServers by viewModel.mcpServers.collectAsState()
    val hooks by viewModel.hooks.collectAsState()

    SettingsPathField(
        label = "Codex home",
        value = state.codexHome.toString(),
        onBrowse = viewModel::requestCodexHome,
    )
    McpSettingsContent(
        servers = mcpServers,
        onAdd = onAddMcp,
        onOpenDetails = onOpenMcp,
        onImport = onImportMcp,
    )
    HookSettingsContent(
        hooks = hooks,
        onAdd = onAddHook,
        onOpenDetails = onOpenHook,
    )
    GlobalAuthenticationAndTitleSettingsContent(
        state = state,
        authentication = authentication,
        accountUsage = accountUsage,
        authenticationDropdown = dropdowns.authentication,
        automaticSessionTitleDropdown = dropdowns.automaticSessionTitle,
        modelDropdown = dropdowns.model,
        reasoningDropdown = dropdowns.reasoning,
        onOpenLogin = viewModel::requestLogin,
        onRefreshUsage = viewModel::refreshUsage,
        onUseReset = viewModel::requestUsageReset,
    )
    Row(modifier = Modifier.fillMaxWidth().background(SettingsFieldBackground)) {
        SettingsDropdownField(
            label = "New line key",
            selectedLabel = state.newLineKey.dialogLabel(),
            dropdownState = dropdowns.newLineKey,
            modifier = Modifier.weight(1f),
        )
        SettingsDropdownField(
            label = "Submit key",
            selectedLabel = state.newLineKey.submitKey.dialogLabel(),
            dropdownState = dropdowns.submitKey,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun GlobalAuthenticationAndTitleSettingsContent(
    state: GlobalSettingsState,
    authentication: SettingsAuthenticationState,
    accountUsage: SettingsAccountUsageState,
    authenticationDropdown: TuiDropdownState,
    automaticSessionTitleDropdown: TuiDropdownState,
    modelDropdown: TuiDropdownState,
    reasoningDropdown: TuiDropdownState,
    onOpenLogin: () -> Unit,
    onRefreshUsage: () -> Unit,
    onUseReset: () -> Unit,
) {
    SettingsDropdownField(
        label = "Authentication",
        selectedLabel = state.authSource.dialogLabel(),
        dropdownState = authenticationDropdown,
    )
    AuthenticationSettingsContent(
        authState = authentication,
        onOpenLogin = onOpenLogin,
    )
    CodexAccountUsageSettingsContent(
        state = accountUsage,
        onRefresh = onRefreshUsage,
        onUseReset = onUseReset,
    )
    SettingsDropdownField(
        label = "Automatic session title",
        selectedLabel = state.sessionTitle.enabled.enabledLabel(),
        dropdownState = automaticSessionTitleDropdown,
    )
    SettingsDropdownField(
        label = "Title model",
        selectedLabel = state.effectiveSessionTitleModel.value,
        dropdownState = modelDropdown,
    )
    SettingsDropdownField(
        label = "Title reasoning",
        selectedLabel = state.sessionTitle.reasoningEffort.displayName(),
        dropdownState = reasoningDropdown,
    )
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
    SettingsPathField(
        label = "Working directory",
        value = snapshot.configuration.workingDirectory.toString(),
        enabled = snapshot.editable,
        onBrowse = onBrowse,
    )
}

@Composable
internal fun SettingsPathField(
    label: String,
    value: String,
    enabled: Boolean = true,
    onBrowse: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SettingsSectionHeaderBackground),
        ) {
            Text("$label ", color = SettingsForeground)
            TuiButton(
                label = "Browse",
                modifier = Modifier.background(SettingsSectionHeaderBackground),
                color = SettingsForeground,
                enabled = enabled,
                onClick = onBrowse,
            )
        }
        Text(
            value = value,
            modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground),
            color = SettingsForeground,
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
        enabled = snapshot.editable,
    )
    SettingsDropdownField(
        label = "Reasoning",
        selectedLabel = configuration.reasoningEffort.displayName(),
        dropdownState = dropdowns.reasoning,
        enabled = snapshot.editable,
    )
    SettingsDropdownField(
        label = "Service tier",
        selectedLabel = configuration.serviceTier.displayName(),
        dropdownState = dropdowns.serviceTier,
        enabled = snapshot.editable,
    )
    SettingsDropdownField(
        label = "Agent mode",
        selectedLabel = configuration.agentMode.displayName(),
        dropdownState = dropdowns.agentMode,
        enabled = snapshot.editable,
    )
    SettingsDropdownField(
        label = "Questions",
        selectedLabel = configuration.requestUserInputMode.displayName(),
        dropdownState = dropdowns.requestUserInputMode,
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
    )
    SettingsDropdownField(
        label = "Reasoning",
        selectedLabel = state.settings.reasoningEffort.displayName(),
        dropdownState = dropdowns.reasoning,
    )
    SettingsDropdownField(
        label = "Service tier",
        selectedLabel = state.settings.serviceTier.displayName(),
        dropdownState = dropdowns.serviceTier,
    )
    SettingsDropdownField(
        label = "Agent mode",
        selectedLabel = state.settings.agentMode.displayName(),
        dropdownState = dropdowns.agentMode,
    )
    SettingsDropdownField(
        label = "Questions",
        selectedLabel = state.settings.requestUserInputMode.displayName(),
        dropdownState = dropdowns.requestUserInputMode,
    )
}

@Composable
internal fun SettingsDropdownField(
    label: String,
    selectedLabel: String,
    dropdownState: TuiDropdownState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier = modifier.fillMaxWidth().background(SettingsFieldBackground)) {
        Text(label, color = SettingsForeground)
        Text(" ")
        TuiDropdownTrigger(
            dropdownState = dropdownState,
            label = selectedLabel,
            modifier = Modifier.background(SettingsFieldBackground),
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
        dropdownState = dropdowns.authentication,
        options = KodexAuthSource.entries.toList(),
        selected = state.authSource,
        optionLabel = KodexAuthSource::dialogLabel,
        backgroundColor = PopupMenuBackground,
        onSelect = viewModel::updateAuthSource,
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.automaticSessionTitle,
        options = listOf(true, false),
        selected = state.sessionTitle.enabled,
        optionLabel = Boolean::enabledLabel,
        backgroundColor = PopupMenuBackground,
        onSelect = viewModel::updateSessionTitleEnabled,
    )
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
    TuiDropdownMenu(
        dropdownState = dropdowns.newLineKey,
        options = NewLineKey.entries.toList(),
        selected = state.newLineKey,
        optionLabel = NewLineKey::dialogLabel,
        backgroundColor = PopupMenuBackground,
        onSelect = viewModel::updateNewLineKey,
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.submitKey,
        options = SubmitKey.entries.toList(),
        selected = state.newLineKey.submitKey,
        optionLabel = SubmitKey::dialogLabel,
        backgroundColor = PopupMenuBackground,
        onSelect = { submitKey -> viewModel.updateNewLineKey(submitKey.newLineKey) },
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
                textStyle = TuiTheme.typography.headline,
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

private class SettingsDropdownStates(
    val authentication: TuiDropdownState,
    val automaticSessionTitle: TuiDropdownState,
    val model: TuiDropdownState,
    val reasoning: TuiDropdownState,
    val serviceTier: TuiDropdownState,
    val agentMode: TuiDropdownState,
    val requestUserInputMode: TuiDropdownState,
    val newLineKey: TuiDropdownState,
    val submitKey: TuiDropdownState,
) {
    fun dismissAll() {
        authentication.dismiss()
        automaticSessionTitle.dismiss()
        model.dismiss()
        reasoning.dismiss()
        serviceTier.dismiss()
        agentMode.dismiss()
        requestUserInputMode.dismiss()
        newLineKey.dismiss()
        submitKey.dismiss()
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

private fun Boolean.enabledLabel(): String = if (this) "Enabled" else "Disabled"

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

internal val SettingsForeground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.onSurface

internal val SettingsHeaderBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.primary

internal val SettingsNavigationBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainer

internal val SettingsHomeBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surface

internal val SettingsFieldBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surface

internal val SettingsSectionHeaderBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainerHigh

internal val SettingsActionBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.primary

internal val PopupMenuBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainer

private const val SettingsMaximumWidth: Int = 84
private const val SettingsNavigationWidth: Int = 18
private const val RenameMaximumWidth: Int = 72
