package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
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
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperation
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperationState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.app.settings.contract.SettingsViewModel
import io.github.stream29.kodex.cli.pathpicker.DirectoryPickerPopup
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.ScrollState
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiDropdownTrigger
import io.github.stream29.kodex.cli.components.TuiInteractionStyle
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.components.verticalScroll
import io.github.stream29.kodex.hook.contract.HookManagedState
import io.github.stream29.kodex.mcp.contract.McpImportDecision
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
    val selectedPage by viewModel.selectedPage.collectAsState()
    val dropdowns = SettingsDropdownStates(
        authentication = rememberTuiDropdownState(),
        model = rememberTuiDropdownState(),
        reasoning = rememberTuiDropdownState(),
        titleModel = rememberTuiDropdownState(),
        titleReasoning = rememberTuiDropdownState(),
        serviceTier = rememberTuiDropdownState(),
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
    var logoutConfirmationOpen by remember(viewModel) { mutableStateOf(false) }
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
        logoutConfirmationOpen = false
        if (selectedPage != SettingsPage.Mcp) {
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
        modifier = Modifier.width(width).height(height).background(SettingsDialogBackground),
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
                        SettingsNavigationButton(
                            label = candidate.settingsLabel(),
                            modifier = Modifier.fillMaxWidth(),
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
                        onRequestLogout = { logoutConfirmationOpen = true },
                    )
                }
            }
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                SettingsActionButton(
                    label = "Close",
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
    if (selectedPage == SettingsPage.OpenAi) {
        UsageResetDialogHost(viewModel.global)
    }
    if (logoutConfirmationOpen) {
        OpenAiLogoutConfirmationDialog(
            onDismiss = { logoutConfirmationOpen = false },
            onConfirm = {
                logoutConfirmationOpen = false
                viewModel.global.logoutKodex()
            },
        )
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
    onRequestLogout: () -> Unit,
) {
    when (page) {
        SettingsPage.General -> GeneralSettingsContent(
            viewModel = viewModel.global,
            dropdowns = dropdowns,
        )

        SettingsPage.OpenAi -> OpenAiSettingsContent(
            viewModel = viewModel.global,
            dropdowns = dropdowns,
            onRequestLogout = onRequestLogout,
        )

        SettingsPage.Mcp -> McpSettingsPageContent(
            viewModel = viewModel.global,
            onAddMcp = onAddMcp,
            onOpenMcp = onOpenMcp,
            onImportMcp = onImportMcp,
        )

        SettingsPage.Hooks -> HookSettingsPageContent(
            viewModel = viewModel.global,
            onAddHook = onAddHook,
            onOpenHook = onOpenHook,
        )

        SettingsPage.CurrentSession -> SessionSettingsContent(viewModel.session, dropdowns)
        SettingsPage.NewSession -> NewSessionSettingsContent(
            viewModel = viewModel.newSession,
            globalViewModel = viewModel.global,
            dropdowns = dropdowns,
        )
    }
}

@Composable
private fun GeneralSettingsContent(
    viewModel: GlobalSettingsViewModel,
    dropdowns: SettingsDropdownStates,
) {
    val state by viewModel.state.collectAsState()

    SettingsSection(title = "General") {
        SettingsPathField(
            label = "Codex home",
            value = state.codexHome.toString(),
            onBrowse = viewModel::requestCodexHome,
        )
    }
    SettingsSection(title = "Sidebars") {
        SettingsSidebarWidthItem(
            label = "Left sidebar width",
            columns = state.sidebars.leftWidth,
            onChange = viewModel::updateLeftSidebarWidth,
        )
        SettingsSidebarWidthItem(
            label = "Right sidebar width",
            columns = state.sidebars.rightWidth,
            onChange = viewModel::updateRightSidebarWidth,
        )
    }
    SettingsSection(title = "Input") {
        SettingsDropdownField(
            label = "New line key",
            selectedLabel = state.newLineKey.dialogLabel(),
            dropdownState = dropdowns.newLineKey,
        )
        SettingsDropdownField(
            label = "Submit key",
            selectedLabel = state.newLineKey.submitKey.dialogLabel(),
            dropdownState = dropdowns.submitKey,
        )
    }
}

@Composable
private fun OpenAiSettingsContent(
    viewModel: GlobalSettingsViewModel,
    dropdowns: SettingsDropdownStates,
    onRequestLogout: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val authentication by viewModel.authentication.collectAsState()
    val operation by viewModel.authenticationOperation.collectAsState()
    val accountUsage by viewModel.accountUsage.collectAsState()
    val operationRunning = operation.isRunning()

    SettingsSection(title = "Account") {
        SettingsDropdownField(
            label = "Authentication",
            selectedLabel = state.authSource.dialogLabel(),
            dropdownState = dropdowns.authentication,
            enabled = !operationRunning,
        )
        AuthenticationSettingsContent(
            authSource = state.authSource,
            authState = authentication,
            operation = operation,
            onOpenLogin = viewModel::requestLogin,
            onReload = viewModel::reloadAuthentication,
            onRequestLogout = onRequestLogout,
            onDismissOperationFailure = viewModel::dismissAuthenticationOperationFailure,
        )
        CodexAccountUsageSettingsContent(
            state = accountUsage,
            onRefresh = viewModel::refreshUsage,
            onUseReset = viewModel::requestUsageReset,
        )
    }
}

@Composable
private fun McpSettingsPageContent(
    viewModel: GlobalSettingsViewModel,
    onAddMcp: () -> Unit,
    onOpenMcp: (io.github.stream29.kodex.app.settings.contract.McpServerSettingsState) -> Unit,
    onImportMcp: () -> Unit,
) {
    val servers by viewModel.mcpServers.collectAsState()
    McpSettingsContent(
        servers = servers,
        onAdd = onAddMcp,
        onOpenDetails = onOpenMcp,
        onImport = onImportMcp,
    )
}

@Composable
private fun HookSettingsPageContent(
    viewModel: GlobalSettingsViewModel,
    onAddHook: () -> Unit,
    onOpenHook: (HookManagedState) -> Unit,
) {
    val hooks by viewModel.hooks.collectAsState()
    HookSettingsContent(
        hooks = hooks,
        onAdd = onAddHook,
        onOpenDetails = onOpenHook,
    )
}

@Composable
internal fun SessionTitleSettingsContent(
    state: GlobalSettingsState,
    modelDropdown: TuiDropdownState,
    reasoningDropdown: TuiDropdownState,
    onUpdateEnabled: (Boolean) -> Unit,
) {
    SettingsSection(title = "Title generation") {
        SettingsCheckboxItem(
            label = "Automatic session title",
            checked = state.sessionTitle.enabled,
            onCheckedChange = onUpdateEnabled,
        )
        SettingsDropdownField(
            label = "Title model",
            selectedLabel = state.effectiveSessionTitleModel.value,
            dropdownState = modelDropdown,
            enabled = state.sessionTitle.enabled,
            supportingText = if (state.sessionTitle.enabled) {
                null
            } else {
                "Available when automatic session titles are enabled."
            },
        )
        SettingsDropdownField(
            label = "Title reasoning",
            selectedLabel = state.sessionTitle.reasoningEffort.displayName(),
            dropdownState = reasoningDropdown,
            enabled = state.sessionTitle.enabled,
            supportingText = "Reasoning effort used to generate automatic session titles.",
        )
    }
}

@Composable
internal fun AuthenticationSettingsContent(
    authSource: KodexAuthSource,
    authState: SettingsAuthenticationState,
    operation: SettingsAuthenticationOperationState,
    onOpenLogin: () -> Unit,
    onReload: () -> Unit,
    onRequestLogout: () -> Unit,
    onDismissOperationFailure: () -> Unit,
) {
    val operationRunning = operation.isRunning()
    Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
        SettingsItem(
            label = "OpenAI account",
            supportingText = authState.settingsSummary(),
        )

        when (authSource) {
            KodexAuthSource.Codex -> {
                SettingsItem(
                    label = "Codex credentials",
                    supportingText =
                        "Managed by Codex CLI. Update credentials there, then reload, " +
                            "or select Kodex to sign in here.",
                    enabled = !operationRunning,
                ) {
                    SettingsActionButton(
                        label = "Reload",
                        enabled = !operationRunning,
                        onClick = onReload,
                    )
                }
            }

            KodexAuthSource.Kodex -> {
                SettingsItem(
                    label = "Browser sign-in",
                    enabled = !operationRunning,
                ) {
                    SettingsActionButton(
                        label = if (authState is SettingsAuthenticationState.Authenticated) {
                            "Sign in again"
                        } else {
                            "Sign in"
                        },
                        enabled = !operationRunning,
                        onClick = onOpenLogin,
                    )
                }
                SettingsItem(
                    label = "Private credentials",
                    enabled = !operationRunning,
                ) {
                    SettingsActionButton(
                        label = "Reload",
                        enabled = !operationRunning,
                        onClick = onReload,
                    )
                }
                if (authState is SettingsAuthenticationState.Authenticated) {
                    SettingsItem(
                        label = "Remove private credentials",
                        enabled = !operationRunning,
                    ) {
                        SettingsDangerButton(
                            label = "Log out",
                            enabled = !operationRunning,
                            onClick = onRequestLogout,
                        )
                    }
                }
            }
        }

        when (operation) {
            SettingsAuthenticationOperationState.Idle -> Unit
            SettingsAuthenticationOperationState.Reloading -> SettingsItem(
                label = "Authentication operation",
                supportingText = "Reloading credentials…",
                enabled = false,
            )

            SettingsAuthenticationOperationState.SigningOut -> SettingsItem(
                label = "Authentication operation",
                supportingText = "Signing out…",
                enabled = false,
            )

            is SettingsAuthenticationOperationState.Failed -> SettingsItem(
                label = "Authentication operation",
                supportingText = operation.failureDescription(),
            ) {
                SettingsActionButton(
                    label = "Dismiss",
                    onClick = onDismissOperationFailure,
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
            SettingsSection(title = "Identity") {
                SessionNameSettingsContent(
                    snapshot = snapshot,
                    onRename = { viewModel.requestRename(snapshot.revision) },
                )
                WorkingDirectorySettingsContent(
                    snapshot = snapshot,
                    onBrowse = { viewModel.requestWorkingDirectory(snapshot.revision) },
                )
            }
            SettingsSection(title = "Model behavior") {
                ConfigurationSettingsContent(snapshot, dropdowns)
            }
        }
    }
}

@Composable
private fun SessionNameSettingsContent(
    snapshot: SessionSettingsSnapshot,
    onRename: () -> Unit,
) {
    SettingsItem(
        label = "Session name",
        supportingText = snapshot.sessionName,
    ) {
        SettingsActionButton(
            label = "Rename",
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
    SettingsItem(
        label = label,
        supportingText = value,
        enabled = enabled,
    ) {
        SettingsActionButton(
            label = "Browse",
            enabled = enabled,
            onClick = onBrowse,
        )
    }
}

@Composable
internal fun SettingsSidebarWidthItem(
    label: String,
    columns: Int,
    onChange: (Int) -> Unit,
) {
    SettingsItem(
        label = label,
        supportingText = "$columns columns",
    ) {
        SettingsActionButton(
            label = "-",
            enabled = columns > MinimumSidebarWidthColumns,
            onClick = { onChange(columns - 1) },
        )
        SettingsActionButton(
            label = "+",
            enabled = columns < Int.MAX_VALUE,
            onClick = { onChange(columns + 1) },
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
        label = "Questions",
        selectedLabel = configuration.requestUserInputMode.displayName(),
        dropdownState = dropdowns.requestUserInputMode,
        enabled = snapshot.editable,
        supportingText = "Controls whether the agent may pause to ask for input.",
    )
}

@Composable
private fun NewSessionSettingsContent(
    viewModel: NewSessionSettingsViewModel,
    globalViewModel: GlobalSettingsViewModel,
    dropdowns: SettingsDropdownStates,
) {
    val state by viewModel.state.collectAsState()
    val globalState by globalViewModel.state.collectAsState()
    SettingsSection(title = "Model behavior") {
        NewSessionConfigurationContent(state, dropdowns)
    }
    SessionTitleSettingsContent(
        state = globalState,
        modelDropdown = dropdowns.titleModel,
        reasoningDropdown = dropdowns.titleReasoning,
        onUpdateEnabled = globalViewModel::updateSessionTitleEnabled,
    )
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
        label = "Questions",
        selectedLabel = state.settings.requestUserInputMode.displayName(),
        dropdownState = dropdowns.requestUserInputMode,
        supportingText = "Controls whether the agent may pause to ask for input.",
    )
}

@Composable
internal fun SettingsDropdownField(
    label: String,
    selectedLabel: String,
    dropdownState: TuiDropdownState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    SettingsItem(
        label = label,
        supportingText = supportingText,
        modifier = modifier,
        enabled = enabled,
    ) {
        TuiDropdownTrigger(
            dropdownState = dropdownState,
            label = selectedLabel,
            color = SettingsForeground,
            interactionStyle = TuiInteractionStyle.PreserveColors,
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
        SettingsPage.General -> GeneralSettingsDropdownMenus(viewModel.global, dropdowns)
        SettingsPage.OpenAi -> OpenAiSettingsDropdownMenus(viewModel.global, dropdowns)
        SettingsPage.Mcp,
        SettingsPage.Hooks,
            -> Unit

        SettingsPage.CurrentSession ->
            SessionSettingsDropdownMenus(viewModel.session, dropdowns)

        SettingsPage.NewSession -> {
            NewSessionSettingsDropdownMenus(viewModel.newSession, dropdowns)
            SessionTitleSettingsDropdownMenus(viewModel.global, dropdowns)
        }
    }
}

@Composable
private fun BoxScope.GeneralSettingsDropdownMenus(
    viewModel: GlobalSettingsViewModel,
    dropdowns: SettingsDropdownStates,
) {
    val state by viewModel.state.collectAsState()
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
private fun BoxScope.OpenAiSettingsDropdownMenus(
    viewModel: GlobalSettingsViewModel,
    dropdowns: SettingsDropdownStates,
) {
    val state by viewModel.state.collectAsState()
    val operation by viewModel.authenticationOperation.collectAsState()
    TuiDropdownMenu(
        dropdownState = dropdowns.authentication,
        options = KodexAuthSource.entries.toList(),
        selected = state.authSource,
        optionLabel = KodexAuthSource::dialogLabel,
        enabled = !operation.isRunning(),
        backgroundColor = PopupMenuBackground,
        onSelect = viewModel::updateAuthSource,
    )
}

@Composable
private fun BoxScope.SessionTitleSettingsDropdownMenus(
    viewModel: GlobalSettingsViewModel,
    dropdowns: SettingsDropdownStates,
) {
    val state by viewModel.state.collectAsState()
    TuiDropdownMenu(
        dropdownState = dropdowns.titleModel,
        options = state.modelOptions,
        selected = state.effectiveSessionTitleModel,
        optionLabel = OpenAiModelId::value,
        enabled = state.sessionTitle.enabled,
        backgroundColor = PopupMenuBackground,
        onSelect = viewModel::updateSessionTitleModel,
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.titleReasoning,
        options = knownReasoningEfforts,
        selected = state.sessionTitle.reasoningEffort,
        optionLabel = ReasoningEffort::displayName,
        enabled = state.sessionTitle.enabled,
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
        dropdownState = dropdowns.requestUserInputMode,
        options = RequestUserInputMode.entries.toList(),
        selected = state.settings.requestUserInputMode,
        optionLabel = RequestUserInputMode::displayName,
        backgroundColor = PopupMenuBackground,
        onSelect = { mode -> viewModel.updateRequestUserInputMode(state.revision, mode) },
    )
}

@Composable
private fun BoxScope.OpenAiLogoutConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4)
        .coerceIn(1, AuthenticationLogoutMaximumWidth)
    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsDialogBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
            Text(
                value = "Log out of OpenAI",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            Text(
                value = "Remove Kodex private credentials? Codex CLI credentials are not affected.",
                color = SettingsForeground,
            )
            TuiDialogActionRow(
                modifier = Modifier.fillMaxWidth().background(SettingsActionBackground),
            ) {
                SettingsActionButton(
                    label = "Cancel",
                    autoFocus = true,
                    onClick = onDismiss,
                )
                SettingsDangerButton(
                    label = "Log out",
                    prominent = true,
                    onClick = onConfirm,
                )
            }
        }
    }
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
        modifier = Modifier.width(width).background(SettingsDialogBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
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
    val model: TuiDropdownState,
    val reasoning: TuiDropdownState,
    val titleModel: TuiDropdownState,
    val titleReasoning: TuiDropdownState,
    val serviceTier: TuiDropdownState,
    val requestUserInputMode: TuiDropdownState,
    val newLineKey: TuiDropdownState,
    val submitKey: TuiDropdownState,
) {
    fun dismissAll() {
        authentication.dismiss()
        model.dismiss()
        reasoning.dismiss()
        titleModel.dismiss()
        titleReasoning.dismiss()
        serviceTier.dismiss()
        requestUserInputMode.dismiss()
        newLineKey.dismiss()
        submitKey.dismiss()
    }
}

internal fun SettingsPage.settingsLabel(): String = when (this) {
    SettingsPage.General -> "General"
    SettingsPage.OpenAi -> "OpenAI"
    SettingsPage.Mcp -> "MCP"
    SettingsPage.Hooks -> "Hooks"
    SettingsPage.CurrentSession -> "Current session"
    SettingsPage.NewSession -> "New session"
}

private fun SettingsAuthenticationState.settingsSummary(): String =
    when (this) {
        is SettingsAuthenticationState.Authenticated -> {
            val identity = email
                ?.let { value -> "Signed in as $value" }
                ?: accountId
                    ?.let { value -> "Signed in as account $value" }
                ?: "Signed in"
            planType?.let { plan -> "$identity Plan: ${plan.rawValue}" } ?: identity
        }

        is SettingsAuthenticationState.Unavailable ->
            "Authentication unavailable: ${reason.settingsDescription()}"
    }

private fun SettingsAuthenticationOperationState.isRunning(): Boolean =
    this === SettingsAuthenticationOperationState.Reloading ||
        this === SettingsAuthenticationOperationState.SigningOut

private fun SettingsAuthenticationOperationState.Failed.failureDescription(): String =
    when (operation) {
        SettingsAuthenticationOperation.Reload ->
            "Could not reload credentials."

        SettingsAuthenticationOperation.Logout ->
            "Could not log out. Existing credentials were kept."
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
    is ReasoningEffort.Custom -> wireName
}

private fun ServiceTier.displayName(): String = when (this) {
    ServiceTier.Default -> "default"
    ServiceTier.Fast -> "fast"
    ServiceTier.Flex -> "flex"
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
)

private const val SettingsMaximumWidth: Int = 84
private const val SettingsNavigationWidth: Int = 18
private const val RenameMaximumWidth: Int = 72
private const val AuthenticationLogoutMaximumWidth: Int = 72
