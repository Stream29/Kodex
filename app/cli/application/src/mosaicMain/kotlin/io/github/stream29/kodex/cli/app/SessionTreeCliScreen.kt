package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.oshai.kotlinlogging.KotlinLogging
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.animation.animateIntAsState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.fillMaxHeight
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.IntrinsicSize
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.cli.agent.AgentHistoryRevertRequest
import io.github.stream29.kodex.cli.agent.AgentRuntimeViewModel
import io.github.stream29.kodex.cli.agent.AgentRuntimeViewState
import io.github.stream29.kodex.cli.auth.KodexAuthState
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiDropdownTrigger
import io.github.stream29.kodex.cli.components.items
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.components.TuiPopupMenu
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.session.AgentRuntimeTreeEntry
import io.github.stream29.kodex.cli.session.RootSessionEntry
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.newsession.NewSessionViewModel
import io.github.stream29.kodex.cli.newsession.NewSessionViewState
import io.github.stream29.kodex.cli.pathpicker.DirectoryPickerPopup
import io.github.stream29.kodex.cli.settings.login.OpenAiLoginPopup
import io.github.stream29.kodex.cli.settings.login.OpenAiLoginViewModel
import io.github.stream29.kodex.cli.settings.login.OpenAiLoginViewModel as createOpenAiLoginViewModel
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.cli.settings.KodexGlobalSettings
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.cli.settings.SubmitKey
import io.github.stream29.kodex.cli.sessiontitle.DefaultSessionTitleModel
import io.github.stream29.kodex.openai.ModeKind
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.utils.logging.agent
import io.github.stream29.kodex.utils.logging.global
import io.github.stream29.kodex.utils.logging.session
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.time.Instant

/** Existing terminal surface backed by session-tree, Agent, and new-session ViewModels. */
@Composable
internal fun SessionTreeCliScreen(viewModel: SessionTreeCliViewModel) {
    val terminal = LocalTerminalState.current
    val applicationState by viewModel.state.collectAsState()
    val selectedTree = applicationState.selectedTree
    val selectedAgent = selectedTree
        ?.agents
        ?.firstOrNull { entry -> entry.selected }
    val activeNewSession = viewModel.activeNewSession()
    val activeNewSessionState = collectNewSessionState(activeNewSession)
    val globalSettings = applicationState.globalSettings
    val authState by viewModel.authStore.state.collectAsState()
    val agentState = collectAgentState(selectedAgent?.viewModel)
    val pendingHistoryRevert = collectPendingHistoryRevert(selectedAgent?.viewModel)
    val scope = rememberCoroutineScope()
    // Keep the final column and row unused. This matches the terminal-safe bounds used by the
    // existing Mosaic screen and avoids a trailing cursor movement scrolling the frame.
    val columns = (terminal.size.columns - 1).coerceAtLeast(1)
    val rows = (terminal.size.rows - 1).coerceAtLeast(1)
    var sidebarPinnedExpanded by remember { mutableStateOf(false) }
    var sidebarHovered by remember { mutableStateOf(false) }
    var shellSessionMenuRequest by remember { mutableStateOf<ShellSessionMenuRequest?>(null) }
    val sidebarExpanded = sidebarPinnedExpanded || sidebarHovered || shellSessionMenuRequest != null
    val sidebarColumns by animateIntAsState(
        targetValue = if (sidebarExpanded) SessionSidebarExpandedColumns else SessionSidebarCollapsedColumns,
        label = "agent sidebar width",
    )
    val contentColumns = (columns - sidebarColumns).coerceAtLeast(1)
    val contentRows = (rows - SessionTabBarRows).coerceAtLeast(0)
    val runtimeDropdowns = RuntimeConfigurationDropdowns.remember(
        owner = selectedAgent?.viewModel ?: activeNewSession,
    )
    var browserOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsRoute by remember { mutableStateOf(SettingsRoute.Global) }
    var openAiLoginViewModel by remember { mutableStateOf<OpenAiLoginViewModel?>(null) }
    var workingDirectoryPickerRequest by remember { mutableStateOf<WorkingDirectoryPickerRequest?>(null) }
    val settingsDropdowns = SettingsDropdownStates(
        model = rememberTuiDropdownState(),
        reasoning = rememberTuiDropdownState(),
        serviceTier = rememberTuiDropdownState(),
        mode = rememberTuiDropdownState(),
    )
    var tabMenuRequest by remember { mutableStateOf<SessionTabMenuRequest?>(null) }
    var historyEntryMenuRequest by remember { mutableStateOf<HistoryEntryMenuRequest?>(null) }
    var renameSessionRequest by remember { mutableStateOf<RenameSessionRequest?>(null) }
    var deleteSessionRequest by remember { mutableStateOf<RootSessionEntry?>(null) }
    val newSessionDefaults = globalSettings.newSession
    val newSessionSettings = activeNewSessionState?.settings ?: newSessionDefaults
    val modelOptions = (
        applicationState.models.map { model -> model.slug } +
            newSessionSettings.model +
            listOfNotNull(
                agentState?.durable?.settings?.model,
                globalSettings.sessionTitle.model,
            ) +
            DefaultSessionTitleModel
        )
        .distinct()
    val activeSettings = agentState?.durable?.settings
    val selectedRoot = selectedTree?.agents?.firstOrNull { entry -> entry.agentId == selectedTree.rootAgentId }
    val activeSessionName = selectedRoot?.viewModel?.state?.value?.durable?.settings?.threadName
        ?.takeIf(String::isNotBlank)
        ?: activeNewSessionState?.threadName?.takeIf(String::isNotBlank)
        ?: when (val target = applicationState.activeTab) {
            is SessionTabTarget.NewSession -> {
                if (target.ordinal == 1) "New session" else "New session ${target.ordinal}"
            }

            is SessionTabTarget.OpenSession -> "Session ${target.sessionIndex}"
        }
    val failureAgentState = agentState
    val failureSessionId = selectedTree?.rootAgentId
    LaunchedEffect(failureSessionId, failureAgentState?.failureStackTrace) {
        failureAgentState?.failureStackTrace?.let { stackTrace ->
            val failureLogger = failureSessionId
                ?.let { sessionId ->
                    SessionTreeLogger
                        .session(sessionId)
                        .agent(failureAgentState.agentId)
                }
                ?: SessionTreeLogger
            failureLogger
                .error { "Agent runtime operation failed for ${failureAgentState.agentId}\n$stackTrace" }
        }
    }
    val openTabTargets = applicationState.tabs.map(SessionTabViewState::target)
    LaunchedEffect(openTabTargets) {
        tabMenuRequest = tabMenuRequest?.takeIf { request -> request.target in openTabTargets }
        renameSessionRequest = renameSessionRequest?.takeIf { request -> request.target in openTabTargets }
    }
    LaunchedEffect(selectedAgent?.viewModel) {
        shellSessionMenuRequest = null
        historyEntryMenuRequest = null
    }
    LaunchedEffect(selectedAgent?.viewModel, agentState?.running) {
        if (agentState?.running == true) {
            selectedAgent?.viewModel?.dismissHistoryRevert()
        }
    }
    DisposableEffect(selectedAgent?.viewModel) {
        val owner = selectedAgent?.viewModel
        onDispose {
            owner?.dismissHistoryRevert()
        }
    }

    Box(modifier = Modifier.width(columns).height(rows)) {
        TuiPopupHost(modifier = Modifier.width(columns).height(rows)) {
            Column(modifier = Modifier.width(columns).height(rows)) {
                SessionTabBar(
                    tabs = applicationState.tabs,
                    columns = columns,
                    onSelectTab = { target ->
                        tabMenuRequest = null
                        shellSessionMenuRequest = null
                        historyEntryMenuRequest = null
                        if (target != applicationState.activeTab) {
                            scope.launch { viewModel.selectTab(target) }
                        }
                    },
                    onOpenTabMenu = { target, initialName, anchor ->
                        shellSessionMenuRequest = null
                        historyEntryMenuRequest = null
                        tabMenuRequest = SessionTabMenuRequest(
                            target = target,
                            initialName = initialName,
                            anchor = anchor,
                        )
                    },
                    onCreateNewSession = { scope.launch { viewModel.createNewSessionTab() } },
                    onOpenSessions = {
                        browserOpen = true
                        scope.launch { viewModel.refreshSessionCatalog() }
                    },
                )
                Row(modifier = Modifier.width(columns).height(contentRows)) {
                    SessionAgentSidebar(
                        tree = selectedTree,
                        expanded = sidebarExpanded,
                        columns = sidebarColumns,
                        rows = contentRows,
                        onHoverChanged = { sidebarHovered = it },
                        onToggleExpanded = { sidebarPinnedExpanded = !sidebarPinnedExpanded },
                        onSelectAgent = { agentId ->
                            shellSessionMenuRequest = null
                            historyEntryMenuRequest = null
                            viewModel.selectAgent(agentId)
                        },
                        onOpenShellSessionMenu = { request ->
                            tabMenuRequest = null
                            historyEntryMenuRequest = null
                            shellSessionMenuRequest = request
                        },
                    )
                    if (activeNewSession != null) {
                        val newSessionTarget = requireNotNull(
                            applicationState.activeTab as? SessionTabTarget.NewSession,
                        )
                        val newSessionState = requireNotNull(activeNewSessionState)
                        NewSessionScreen(
                            viewModel = activeNewSession,
                            state = newSessionState,
                            columns = contentColumns,
                            rows = contentRows,
                            newLineKey = globalSettings.newLineKey,
                            dropdowns = runtimeDropdowns,
                            onSubmit = {
                                scope.launch { viewModel.submitNewSessionComposer(newSessionTarget) }
                            },
                            onOpenSettings = {
                                settingsRoute = defaultSettingsRoute(newSessionTarget)
                                settingsOpen = true
                            },
                        )
                    } else if (selectedAgent != null) {
                        val runtimeState = requireNotNull(agentState)
                        AgentRuntimeScreen(
                            agent = selectedAgent,
                            runtimeState = runtimeState,
                            columns = contentColumns,
                            rows = contentRows,
                            newLineKey = globalSettings.newLineKey,
                            fallbackSettings = globalSettings.newSession,
                            dropdowns = runtimeDropdowns,
                            onOpenHistoryEntryContextMenu = { generation, storageIndex, anchor ->
                                val target =
                                    applicationState.activeTab as? SessionTabTarget.OpenSession
                                if (
                                    target != null &&
                                    runtimeState.canReplaceCommittedHistory &&
                                    selectedAgent.viewModel.session.runtime.runningTurn.value == null
                                ) {
                                    tabMenuRequest = null
                                    shellSessionMenuRequest = null
                                    historyEntryMenuRequest = HistoryEntryMenuRequest(
                                        sessionIndex = target.sessionIndex,
                                        agentId = selectedAgent.agentId,
                                        generation = generation,
                                        storageIndex = storageIndex,
                                        anchor = anchor,
                                    )
                                }
                            },
                            onOpenSettings = {
                                historyEntryMenuRequest = null
                                settingsRoute = SettingsRoute.Global
                                settingsOpen = true
                            },
                        )
                    } else {
                        Box(modifier = Modifier.width(contentColumns).height(contentRows)) {
                            Text("Opening session…", textStyle = TextStyle.Dim)
                        }
                    }
                }
            }
            val openTabMenuRequest = tabMenuRequest
            if (openTabMenuRequest != null) {
                TuiPopupMenu(
                    expanded = true,
                    anchor = openTabMenuRequest.anchor,
                    onDismissRequest = { tabMenuRequest = null },
                    backgroundColor = PopupMenuBackground,
                ) {
                    TuiPopupMenuItem(key = "rename-session", onClick = {
                        tabMenuRequest = null
                        renameSessionRequest = RenameSessionRequest(
                            target = openTabMenuRequest.target,
                            initialName = openTabMenuRequest.initialName,
                        )
                    }) { Text("Rename") }
                    TuiPopupMenuItem(key = "close-tab", onClick = {
                        tabMenuRequest = null
                        scope.launch { viewModel.closeTab(openTabMenuRequest.target) }
                    }) { Text("Close session") }
                }
            }
            ShellSessionContextMenu(
                request = shellSessionMenuRequest,
                onDismissRequest = { shellSessionMenuRequest = null },
            )
            HistoryEntryContextMenu(
                request = historyEntryMenuRequest,
                activeSessionIndex = (applicationState.activeTab as? SessionTabTarget.OpenSession)
                    ?.sessionIndex,
                selectedAgent = selectedAgent,
                state = agentState,
                onDismissRequest = { historyEntryMenuRequest = null },
                onRevert = { request ->
                    historyEntryMenuRequest = null
                    runCatching {
                        selectedAgent?.viewModel?.requestHistoryRevert(request.storageIndex)
                    }
                },
                onFork = { request ->
                    historyEntryMenuRequest = null
                    scope.launch {
                        runCatching {
                            viewModel.forkHistoryEntry(
                                sessionIndex = request.sessionIndex,
                                agentId = request.agentId,
                                storageIndex = request.storageIndex,
                            )
                        }
                    }
                },
            )
            if (selectedAgent != null && agentState != null) {
                AgentRuntimeStatusMenus(
                    viewModel = selectedAgent.viewModel,
                    state = agentState,
                    fallbackSettings = globalSettings.newSession,
                    models = applicationState.models,
                    modelOptions = modelOptions,
                    dropdowns = runtimeDropdowns,
                )
            } else if (activeNewSession != null && activeNewSessionState != null) {
                NewSessionStatusMenus(
                    viewModel = activeNewSession,
                    state = activeNewSessionState,
                    models = applicationState.models,
                    modelOptions = modelOptions,
                    dropdowns = runtimeDropdowns,
                )
            }
            if (browserOpen) {
                SessionTreeBrowserDialog(
                    viewModel = viewModel,
                    state = applicationState,
                    scope = scope,
                    onDismiss = { browserOpen = false },
                    onDelete = { session -> deleteSessionRequest = session },
                )
            }
            deleteSessionRequest?.let { session ->
                DeleteSessionDialog(
                    session = session,
                    onDismiss = { deleteSessionRequest = null },
                    onDelete = {
                        scope.launch {
                            viewModel.delete(session.sessionIndex)
                            deleteSessionRequest = null
                        }
                    },
                )
            }
            val activeSessionIndex =
                (applicationState.activeTab as? SessionTabTarget.OpenSession)?.sessionIndex
            if (
                activeSessionIndex != null &&
                selectedAgent != null &&
                pendingHistoryRevert != null &&
                agentState?.canReplaceCommittedHistory == true &&
                selectedAgent.viewModel.session.runtime.runningTurn.value == null
            ) {
                HistoryRevertDialog(
                    onDismiss = selectedAgent.viewModel::dismissHistoryRevert,
                    onRevert = {
                        scope.launch {
                            runCatching {
                                viewModel.confirmHistoryRevert(
                                    sessionIndex = activeSessionIndex,
                                    agentId = selectedAgent.agentId,
                                )
                            }
                        }
                    },
                )
            }
            if (settingsOpen) {
                GlobalSettingsDialog(
                    state = globalSettings,
                    authState = authState,
                    agent = selectedAgent?.viewModel,
                    agentState = agentState,
                    route = settingsRoute,
                    onRouteSelected = { settingsRoute = it },
                    dropdowns = settingsDropdowns,
                    onDismiss = {
                        workingDirectoryPickerRequest = null
                        openAiLoginViewModel = null
                        settingsDropdowns.dismissAll()
                        settingsRoute = SettingsRoute.Global
                        settingsOpen = false
                    },
                    onNewLineKey = { key -> scope.launch { viewModel.updateNewLineKey(key) } },
                    onAuthSource = { source -> scope.launch { viewModel.updateAuthSource(source) } },
                    onUpdateSessionTitle = { transform ->
                        scope.launch { viewModel.updateSessionTitleSettings(transform) }
                    },
                    onOpenLogin = {
                        if (openAiLoginViewModel == null) {
                            openAiLoginViewModel = scope.createOpenAiLoginViewModel(viewModel.authStore)
                        }
                    },
                    onBrowseWorkingDirectory = { directory ->
                        workingDirectoryPickerRequest = when {
                            activeNewSession != null -> WorkingDirectoryPickerRequest(
                                initialDirectory = directory,
                                target = WorkingDirectoryTarget.NewSession(activeNewSession),
                            )

                            selectedAgent != null -> WorkingDirectoryPickerRequest(
                                initialDirectory = directory,
                                target = WorkingDirectoryTarget.Agent(selectedAgent.viewModel),
                            )

                            else -> null
                        }
                    },
                    newSessionSettings = newSessionDefaults,
                    newSessionState = activeNewSessionState,
                    sessionName = activeSessionName,
                    onRenameSession = {
                        workingDirectoryPickerRequest = null
                        openAiLoginViewModel = null
                        settingsDropdowns.dismissAll()
                        settingsRoute = SettingsRoute.Global
                        settingsOpen = false
                        renameSessionRequest = RenameSessionRequest(
                            target = applicationState.activeTab,
                            initialName = activeSessionName,
                        )
                    },
                )
                when (settingsRoute) {
                    SettingsRoute.Global -> SessionTitleSettingsDropdownMenus(
                        settings = globalSettings.sessionTitle,
                        models = modelOptions,
                        dropdowns = settingsDropdowns,
                        onUpdate = { transform ->
                            scope.launch { viewModel.updateSessionTitleSettings(transform) }
                        },
                    )

                    SettingsRoute.Session -> when {
                        activeSettings != null -> AgentSettingsDropdownMenus(
                            settings = activeSettings,
                            models = modelOptions,
                            dropdowns = settingsDropdowns,
                            enabled = !agentState.running,
                            onUpdate = { transform ->
                                scope.launch { selectedAgent?.viewModel?.updateSettings(transform) }
                            },
                        )

                        activeNewSession != null && activeNewSessionState != null ->
                            NewSessionSettingsDropdownMenus(
                                settings = activeNewSessionState.settings,
                                models = modelOptions,
                                dropdowns = settingsDropdowns,
                                onUpdate = { transform ->
                                    scope.launch { activeNewSession.updateSettings(transform) }
                                },
                            )
                    }

                    SettingsRoute.NewSession -> NewSessionSettingsDropdownMenus(
                        settings = newSessionDefaults,
                        models = modelOptions,
                        dropdowns = settingsDropdowns,
                        onUpdate = { transform ->
                            scope.launch { viewModel.updateNewSessionDefaults(transform) }
                        },
                    )
                }
            }
            renameSessionRequest?.let { request ->
                RenameSessionDialog(
                    initialName = request.initialName,
                    onDismiss = { renameSessionRequest = null },
                    onRename = { threadName ->
                        renameSessionRequest = null
                        scope.launch { viewModel.renameSession(request.target, threadName) }
                    },
                )
            }
            workingDirectoryPickerRequest?.let { request ->
                DirectoryPickerPopup(
                    initialDirectory = request.initialDirectory,
                    onDismissRequest = { workingDirectoryPickerRequest = null },
                    onDirectorySelected = { directory ->
                        workingDirectoryPickerRequest = null
                        scope.launch {
                            when (val target = request.target) {
                                is WorkingDirectoryTarget.Agent ->
                                    target.viewModel.updateSettings { current -> current.copy(cwd = directory) }

                                is WorkingDirectoryTarget.NewSession ->
                                    target.viewModel.updateWorkingDirectory(directory)
                            }
                        }
                    },
                )
            }
            openAiLoginViewModel?.let { loginViewModel ->
                OpenAiLoginPopup(
                    viewModel = loginViewModel,
                    onDismissRequest = { openAiLoginViewModel = null },
                )
            }
        }
    }
}

private val SessionTreeLogger by lazy {
    KotlinLogging.logger {}.global()
}

@Composable
private fun collectNewSessionState(viewModel: NewSessionViewModel?): NewSessionViewState? {
    if (viewModel == null) return null
    return key(viewModel) {
        val state by viewModel.state.collectAsState()
        state
    }
}

@Composable
private fun collectAgentState(viewModel: AgentRuntimeViewModel?): AgentRuntimeViewState? {
    if (viewModel == null) return null
    return key(viewModel) {
        val state by viewModel.state.collectAsState()
        state
    }
}

@Composable
private fun collectPendingHistoryRevert(
    viewModel: AgentRuntimeViewModel?,
): AgentHistoryRevertRequest? {
    if (viewModel == null) return null
    return key(viewModel) {
        val request by viewModel.pendingHistoryRevert.collectAsState()
        request
    }
}

@Composable
private fun BoxScope.HistoryEntryContextMenu(
    request: HistoryEntryMenuRequest?,
    activeSessionIndex: Int?,
    selectedAgent: AgentRuntimeTreeEntry?,
    state: AgentRuntimeViewState?,
    onDismissRequest: () -> Unit,
    onRevert: (HistoryEntryMenuRequest) -> Unit,
    onFork: (HistoryEntryMenuRequest) -> Unit,
) {
    val openRequest = request ?: return
    val targetMatches = selectedAgent != null &&
        activeSessionIndex == openRequest.sessionIndex &&
        selectedAgent.agentId == openRequest.agentId &&
        state != null &&
        state.canReplaceCommittedHistory &&
        selectedAgent.viewModel.session.runtime.runningTurn.value == null
    LaunchedEffect(openRequest, targetMatches) {
        if (!targetMatches) onDismissRequest()
    }
    if (!targetMatches) return

    val anchorPlaced = openRequest.anchor.isPlaced
    LaunchedEffect(openRequest, anchorPlaced) {
        if (!anchorPlaced) onDismissRequest()
    }
    if (!anchorPlaced) return

    LaunchedEffect(openRequest, selectedAgent.historyViewModel) {
        selectedAgent.historyViewModel.window.first { window ->
            window.generation != openRequest.generation ||
                window.entries.none { entry -> entry.index == openRequest.storageIndex }
        }
        onDismissRequest()
    }
    TuiPopupMenu(
        expanded = true,
        anchor = openRequest.anchor,
        onDismissRequest = onDismissRequest,
        backgroundColor = PopupMenuBackground,
    ) {
        TuiPopupMenuItem(
            key = "revert-history-here",
            onClick = { onRevert(openRequest) },
        ) {
            Text("Revert here")
        }
        TuiPopupMenuItem(
            key = "fork-history-here",
            onClick = { onFork(openRequest) },
        ) {
            Text("Fork here")
        }
    }
}

internal val AgentRuntimeViewState.canReplaceCommittedHistory: Boolean
    get() {
        if (running) return false
        return when (agentState) {
            KodexAgentStateValue.Empty,
            KodexAgentStateValue.UserMessage,
            KodexAgentStateValue.AssistantMessage,
            is KodexAgentStateValue.ToolPending,
            KodexAgentStateValue.ToolCompleted,
                -> true

            KodexAgentStateValue.ExternalWrite,
            is KodexAgentStateValue.RequestResponse,
            KodexAgentStateValue.Compacting,
                -> false
        }
    }

@Composable
private fun BoxScope.SessionTreeBrowserDialog(
    viewModel: SessionTreeCliViewModel,
    state: SessionTreeCliState,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onDelete: (RootSessionEntry) -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, 84)
    val bodyRows = sessionBrowserVisibleRows(LocalTerminalState.current.size.rows)
    val selectedSessionIndex = (state.activeTab as? SessionTabTarget.OpenSession)?.sessionIndex
    val selectedSessionPosition = state.sessions.sessions.indexOfFirst { entry ->
        entry.sessionIndex == selectedSessionIndex
    }.coerceAtLeast(0)
    val sessionListState =
        remember(state.sessions.sessions.map { it.sessionIndex }, selectedSessionPosition, bodyRows) {
            LazyListState(
                initialFirstVisibleItemIndex = initialBrowserScrollOffset(
                    state.sessions.sessions.size, bodyRows, selectedSessionPosition,
                )
            )
        }
    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsDialogHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                value = "Sessions",
                modifier = Modifier.fillMaxWidth().background(SettingsDialogHeaderBackground),
                color = SettingsDialogForeground,
                textStyle = TextStyle.Bold,
            )
            Box(
                modifier = Modifier.fillMaxWidth().height(bodyRows)
                    .background(SettingsDialogNavigationBackground),
            ) {
                if (state.sessions.sessions.isEmpty()) {
                    Text("No sessions", color = SettingsDialogForeground)
                } else {
                    LazyColumn(state = sessionListState, modifier = Modifier.fillMaxWidth().height(bodyRows)) {
                        items(state.sessions.sessions, key = { it.sessionIndex }) { session ->
                            val background = if (session.sessionIndex == selectedSessionIndex) {
                                SettingsDialogSelectionBackground
                            } else {
                                SettingsDialogNavigationBackground
                            }
                            Row(modifier = Modifier.fillMaxWidth().background(background)) {
                                TuiButton(
                                    label = session.sessionBrowserLabel(sessionBrowserTitleColumns(width)),
                                    color = SettingsDialogForeground,
                                    autoFocus = session.sessionIndex == selectedSessionIndex,
                                    modifier = Modifier.weight(1f).background(background),
                                    onClick = {
                                        scope.launch {
                                            viewModel.open(session.sessionIndex)
                                            onDismiss()
                                        }
                                    },
                                )
                                Text(" ")
                                TuiButton(
                                    label = SessionBrowserDeleteLabel,
                                    color = SettingsDialogForeground,
                                    modifier = Modifier.background(background),
                                    onClick = { onDelete(session) },
                                )
                            }
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().background(SettingsDialogActionBackground)) {
                TuiButton(label = "Close", color = SettingsDialogForeground, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun BoxScope.DeleteSessionDialog(
    session: RootSessionEntry,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, 72)
    val title = session.threadName?.takeIf(String::isNotBlank) ?: "Session ${session.sessionIndex}"
    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsDialogHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                value = "Delete session?",
                modifier = Modifier.fillMaxWidth().background(SettingsDialogHeaderBackground),
                color = SettingsDialogForeground,
                textStyle = TextStyle.Bold,
            )
            Text(
                value = title.ellipsizeToTerminalWidth(width),
                modifier = Modifier.fillMaxWidth(),
                color = SettingsDialogForeground,
            )
            Text("This cannot be undone.", color = SettingsDialogForeground, textStyle = TextStyle.Dim)
            Row(modifier = Modifier.fillMaxWidth().background(SettingsDialogActionBackground)) {
                TuiButton(label = "Delete", color = SettingsDialogForeground, onClick = onDelete)
                Text(" ")
                TuiButton(
                    label = "Cancel",
                    color = SettingsDialogForeground,
                    autoFocus = true,
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.HistoryRevertDialog(
    onDismiss: () -> Unit,
    onRevert: () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, 72)
    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsDialogHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                value = "Revert history here?",
                modifier = Modifier.fillMaxWidth().background(SettingsDialogHeaderBackground),
                color = SettingsDialogForeground,
                textStyle = TextStyle.Bold,
            )
            Text(
                value = "Keep the selected history entry and remove everything after it.",
                modifier = Modifier.fillMaxWidth(),
                color = SettingsDialogForeground,
            )
            Text("This cannot be undone.", color = SettingsDialogForeground, textStyle = TextStyle.Dim)
            Row(modifier = Modifier.fillMaxWidth().background(SettingsDialogActionBackground)) {
                TuiButton(label = "Revert", color = SettingsDialogForeground, onClick = onRevert)
                Text(" ")
                TuiButton(
                    label = "Cancel",
                    color = SettingsDialogForeground,
                    autoFocus = true,
                    onClick = onDismiss,
                )
            }
        }
    }
}

private fun initialBrowserScrollOffset(itemCount: Int, visibleItemCount: Int, selectedIndex: Int): Int =
    (selectedIndex - visibleItemCount / 2).coerceIn(0, (itemCount - visibleItemCount).coerceAtLeast(0))

internal fun sessionBrowserVisibleRows(terminalRows: Int): Int =
    (terminalRows - SessionBrowserDialogRows).coerceIn(1, SessionBrowserMaximumListRows)

private fun sessionBrowserTitleColumns(dialogWidth: Int): Int =
    (
        dialogWidth -
            SessionBrowserListHorizontalPaddingColumns -
            "[$SessionBrowserDeleteLabel]".terminalCellWidth() -
            SessionBrowserItemGapColumns
        ).coerceAtLeast(1)

internal fun RootSessionEntry.sessionBrowserLabel(
    maximumColumns: Int,
    now: Instant = Clock.System.now(),
): String {
    val title = threadName?.takeIf(String::isNotBlank) ?: "Session $sessionIndex"
    val lastActivity = lastActivityAt?.relativeTimeFrom(now)
        ?: return title.ellipsizeToTerminalWidth(maximumColumns)
    val suffix = " · $lastActivity"
    val titleWidth = maximumColumns - suffix.terminalCellWidth()
    return if (titleWidth > 0) {
        title.ellipsizeToTerminalWidth(titleWidth) + suffix
    } else {
        (title + suffix).ellipsizeToTerminalWidth(maximumColumns)
    }
}

private fun Instant.relativeTimeFrom(now: Instant): String {
    val seconds = (now - this).inWholeSeconds.coerceAtLeast(0L)
    return when {
        seconds < 60L -> "now"
        seconds < 60L * 60 -> "${seconds / 60L}m ago"
        seconds < 24L * 60 * 60 -> "${seconds / (60L * 60)}h ago"
        else -> "${seconds / (24L * 60 * 60)}d ago"
    }
}

private const val SessionBrowserMaximumListRows: Int = 16
private const val SessionBrowserDialogRows: Int = 2
private const val SessionBrowserDeleteLabel: String = "Delete"
private const val SessionBrowserListHorizontalPaddingColumns: Int = 2
private const val SessionBrowserItemGapColumns: Int = 1

private data class SessionTabMenuRequest(
    val target: SessionTabTarget,
    val initialName: String,
    val anchor: TuiPopupAnchor,
)

private data class HistoryEntryMenuRequest(
    val sessionIndex: Int,
    val agentId: String,
    val generation: Long,
    val storageIndex: Int,
    val anchor: TuiPopupAnchor,
)

private data class RenameSessionRequest(
    val target: SessionTabTarget,
    val initialName: String,
)

@Composable
private fun BoxScope.RenameSessionDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, 72)
    val input = remember(initialName) {
        TextInputState(TextInputValue(text = initialName, cursorOffset = initialName.length))
    }
    val normalizedName = input.value.text.trim()
    val layout = TextInputLayout.create(value = input.value, width = width)
    fun confirm() {
        input.value.text.trim().takeIf(String::isNotEmpty)?.let(onRename)
    }

    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsDialogHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogHomeBackground)) {
            Text(
                value = "Rename session",
                modifier = Modifier.fillMaxWidth().background(SettingsDialogHeaderBackground),
                color = SettingsDialogForeground,
                textStyle = TextStyle.Bold,
            )
            Text("Session name", color = SettingsDialogForeground)
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
            Row(modifier = Modifier.fillMaxWidth().background(SettingsDialogActionBackground)) {
                TuiButton(
                    label = "Save",
                    color = SettingsDialogForeground,
                    enabled = normalizedName.isNotEmpty(),
                    onClick = ::confirm,
                )
                Text(" ")
                TuiButton(label = "Cancel", color = SettingsDialogForeground, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun BoxScope.GlobalSettingsDialog(
    state: KodexGlobalSettings,
    authState: KodexAuthState,
    agent: AgentRuntimeViewModel?,
    agentState: AgentRuntimeViewState?,
    route: SettingsRoute,
    onRouteSelected: (SettingsRoute) -> Unit,
    dropdowns: SettingsDropdownStates,
    onDismiss: () -> Unit,
    onNewLineKey: (NewLineKey) -> Unit,
    onAuthSource: (KodexAuthSource) -> Unit,
    onUpdateSessionTitle: ((SessionTitleSettings) -> SessionTitleSettings) -> Unit,
    onOpenLogin: () -> Unit,
    onBrowseWorkingDirectory: (Path) -> Unit,
    newSessionSettings: KodexNewSessionSettings,
    newSessionState: NewSessionViewState?,
    sessionName: String?,
    onRenameSession: () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, 84)
    val navigationWidth = SettingsDialogNavigationWidth.coerceAtMost((width - 1).coerceAtLeast(1))
    val contentWidth = (width - navigationWidth).coerceAtLeast(1)
    val configuration = agentState?.durable?.settings
    TuiDialog(onDismissRequest = onDismiss, modifier = Modifier.width(width).background(SettingsDialogHomeBackground)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                value = "Settings",
                modifier = Modifier.fillMaxWidth().background(SettingsDialogHeaderBackground),
                color = SettingsDialogForeground,
                textStyle = TextStyle.Bold,
            )
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                Column(
                    modifier = Modifier.width(navigationWidth).fillMaxHeight()
                        .background(SettingsDialogNavigationBackground),
                ) {
                    SettingsRoute.entries.forEach { candidate ->
                        TuiButton(
                            label = candidate.label,
                            modifier = Modifier.fillMaxWidth().background(
                                if (candidate == route) SettingsDialogSelectionBackground else SettingsDialogNavigationBackground,
                            ),
                            color = SettingsDialogForeground,
                            onClick = { onRouteSelected(candidate) },
                        )
                    }
                }
                Column(
                    modifier = Modifier.width(contentWidth).fillMaxHeight().background(SettingsDialogHomeBackground),
                ) {
                    when (route) {
                        SettingsRoute.Global -> GlobalSettingsContent(
                            state = state,
                            authState = authState,
                            dropdowns = dropdowns,
                            onNewLineKey = onNewLineKey,
                            onAuthSource = onAuthSource,
                            onUpdateSessionTitle = onUpdateSessionTitle,
                            onOpenLogin = onOpenLogin,
                        )

                        SettingsRoute.Session -> {
                            sessionName?.let { name ->
                                SessionNameSettingsContent(name = name, onRename = onRenameSession)
                            }
                            when {
                                configuration != null && agent != null -> AgentSettingsContent(
                                    settings = configuration,
                                    dropdowns = dropdowns,
                                    enabled = !agentState.running,
                                    onBrowseWorkingDirectory = onBrowseWorkingDirectory,
                                )

                                newSessionState != null -> NewSessionDraftSettingsContent(
                                    state = newSessionState,
                                    dropdowns = dropdowns,
                                    onBrowseWorkingDirectory = onBrowseWorkingDirectory,
                                )

                                else -> Text(
                                    value = "No selected session",
                                    modifier = Modifier.fillMaxWidth().background(SettingsDialogHomeBackground),
                                    color = SettingsDialogForeground,
                                )
                            }
                        }

                        SettingsRoute.NewSession -> NewSessionSettingsContent(
                            settings = newSessionSettings,
                            dropdowns = dropdowns,
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().background(SettingsDialogActionBackground)) {
                TuiButton(label = "Close", color = SettingsDialogForeground, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun GlobalSettingsContent(
    state: KodexGlobalSettings,
    authState: KodexAuthState,
    dropdowns: SettingsDropdownStates,
    onNewLineKey: (NewLineKey) -> Unit,
    onAuthSource: (KodexAuthSource) -> Unit,
    onUpdateSessionTitle: ((SessionTitleSettings) -> SessionTitleSettings) -> Unit,
    onOpenLogin: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogHomeBackground)) {
        Text("Codex home", color = SettingsDialogForeground)
        Text(state.codexHome.toString(), modifier = Modifier.fillMaxWidth(), color = SettingsDialogForeground)
    }
    SettingsChoiceGroup(
        label = "Authentication",
        options = KodexAuthSource.entries.toList(),
        selected = state.authSource,
        optionLabel = KodexAuthSource::dialogLabel,
        background = SettingsDialogHomeBackground,
        enabled = true,
        onSelect = onAuthSource,
    )
    SettingsChoiceGroup(
        label = "Automatic session title",
        options = listOf(true, false),
        selected = state.sessionTitle.enabled,
        optionLabel = { enabled -> if (enabled) "Enabled" else "Disabled" },
        background = SettingsDialogHomeBackground,
        enabled = true,
        onSelect = { enabled ->
            onUpdateSessionTitle { current -> current.copy(enabled = enabled) }
        },
    )
    SettingsDropdownField(
        label = "Title model",
        selectedLabel = (state.sessionTitle.model ?: DefaultSessionTitleModel).value,
        dropdownState = dropdowns.model,
        background = SettingsDialogNewLineBackground,
    )
    SettingsDropdownField(
        label = "Title reasoning",
        selectedLabel = state.sessionTitle.reasoningEffort.displayName(),
        dropdownState = dropdowns.reasoning,
        background = SettingsDialogSubmitKeyBackground,
    )
    AuthenticationSettingsContent(authState = authState, onOpenLogin = onOpenLogin)
    Row(modifier = Modifier.fillMaxWidth().background(SettingsDialogNewLineBackground)) {
        Column(modifier = Modifier.weight(1f).background(SettingsDialogNewLineBackground)) {
            Text("New line key", color = SettingsDialogForeground)
            Row {
                NewLineKey.entries.forEachIndexed { index, key ->
                    if (index != 0) Text(" ")
                    TuiButton(
                        label = key.dialogLabel(),
                        modifier = Modifier.background(if (key == state.newLineKey) SettingsDialogSelectionBackground else SettingsDialogNewLineBackground),
                        color = SettingsDialogForeground,
                        onClick = { onNewLineKey(key) },
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f).background(SettingsDialogSubmitKeyBackground)) {
            Text("Submit key", color = SettingsDialogForeground)
            Row {
                SubmitKey.entries.forEachIndexed { index, key ->
                    if (index != 0) Text(" ")
                    TuiButton(
                        label = key.dialogLabel(),
                        modifier = Modifier.background(if (key == state.newLineKey.submitKey) SettingsDialogSelectionBackground else SettingsDialogSubmitKeyBackground),
                        color = SettingsDialogForeground,
                        onClick = { onNewLineKey(key.newLineKey) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun AuthenticationSettingsContent(
    authState: KodexAuthState,
    onOpenLogin: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogHomeBackground)) {
        Text("OpenAI account", color = SettingsDialogForeground)
        when (authState) {
            is KodexAuthState.Authenticated -> {
                val account = authState.value
                val identity = account.email
                    ?.takeIf(String::isNotBlank)
                    ?.let { email -> "Signed in as $email" }
                    ?: account.accountId
                        ?.takeIf(String::isNotBlank)
                        ?.let { accountId -> "Signed in as account $accountId" }
                    ?: "Signed in"
                Text(identity, modifier = Modifier.fillMaxWidth(), color = SettingsDialogForeground)
                account.planType?.let { plan ->
                    Text(
                        value = "Plan: ${plan.rawValue}",
                        modifier = Modifier.fillMaxWidth(),
                        color = SettingsDialogForeground,
                    )
                }
            }

            is KodexAuthState.Unavailable -> {
                Text("Authentication unavailable", color = SettingsDialogForeground)
                Text(
                    value = authState.message,
                    modifier = Modifier.fillMaxWidth(),
                    color = SettingsDialogForeground,
                )
            }
        }
        TuiButton(
            label = "Sign in",
            modifier = Modifier.background(SettingsDialogHomeBackground),
            color = SettingsDialogForeground,
            onClick = onOpenLogin,
        )
    }
}

@Composable
private fun SessionNameSettingsContent(
    name: String,
    onRename: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogHomeBackground)) {
        Text("Session name", color = SettingsDialogForeground)
        Text(value = name, modifier = Modifier.fillMaxWidth(), color = SettingsDialogForeground)
        TuiButton(
            label = "Rename",
            modifier = Modifier.background(SettingsDialogHomeBackground),
            color = SettingsDialogForeground,
            onClick = onRename,
        )
    }
}

@Composable
private fun AgentSettingsContent(
    settings: io.github.stream29.kodex.openai.KodexAgentSettings,
    dropdowns: SettingsDropdownStates,
    enabled: Boolean,
    onBrowseWorkingDirectory: (Path) -> Unit,
) {
    WorkingDirectorySettingsContent(
        workingDirectory = settings.cwd,
        enabled = enabled,
        onBrowse = onBrowseWorkingDirectory,
    )
    SettingsDropdownField(
        label = "Model",
        selectedLabel = settings.model.value,
        dropdownState = dropdowns.model,
        background = SettingsDialogHomeBackground,
        enabled = enabled,
    )
    SettingsDropdownField(
        label = "Reasoning",
        selectedLabel = settings.reasoning.effort.displayName(),
        dropdownState = dropdowns.reasoning,
        background = SettingsDialogNewLineBackground,
        enabled = enabled,
    )
    SettingsDropdownField(
        label = "Service tier",
        selectedLabel = settings.serviceTier.displayName(),
        dropdownState = dropdowns.serviceTier,
        background = SettingsDialogSubmitKeyBackground,
        enabled = enabled,
    )
    SettingsDropdownField(
        label = "Mode",
        selectedLabel = settings.collaborationMode.displayName(),
        dropdownState = dropdowns.mode,
        background = SettingsDialogModeBackground,
        enabled = enabled,
    )
}

@Composable
internal fun NewSessionDraftSettingsContent(
    state: NewSessionViewState,
    dropdowns: SettingsDropdownStates,
    onBrowseWorkingDirectory: (Path) -> Unit,
) {
    WorkingDirectorySettingsContent(
        workingDirectory = state.workingDirectory,
        enabled = !state.creating,
        onBrowse = onBrowseWorkingDirectory,
    )
    NewSessionSettingsContent(
        settings = state.settings,
        dropdowns = dropdowns,
        enabled = !state.creating,
    )
}

@Composable
private fun WorkingDirectorySettingsContent(
    workingDirectory: Path,
    enabled: Boolean,
    onBrowse: (Path) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogHomeBackground)) {
        Text("Working directory", color = SettingsDialogForeground)
        Text(
            value = workingDirectory.toString(),
            modifier = Modifier.fillMaxWidth(),
            color = SettingsDialogForeground,
        )
        TuiButton(
            label = "Browse",
            modifier = Modifier.background(SettingsDialogHomeBackground),
            color = SettingsDialogForeground,
            enabled = enabled,
            onClick = { onBrowse(workingDirectory) },
        )
    }
}

@Composable
private fun NewSessionSettingsContent(
    settings: io.github.stream29.kodex.cli.settings.KodexNewSessionSettings,
    dropdowns: SettingsDropdownStates,
    enabled: Boolean = true,
) {
    SettingsDropdownField(
        label = "Model",
        selectedLabel = settings.model.value,
        dropdownState = dropdowns.model,
        background = SettingsDialogHomeBackground,
        enabled = enabled,
    )
    SettingsDropdownField(
        label = "Reasoning",
        selectedLabel = settings.reasoningEffort.displayName(),
        dropdownState = dropdowns.reasoning,
        background = SettingsDialogNewLineBackground,
        enabled = enabled,
    )
    SettingsDropdownField(
        label = "Service tier",
        selectedLabel = settings.serviceTier.displayName(),
        dropdownState = dropdowns.serviceTier,
        background = SettingsDialogSubmitKeyBackground,
        enabled = enabled,
    )
    SettingsDropdownField(
        label = "Mode",
        selectedLabel = settings.mode.displayName(),
        dropdownState = dropdowns.mode,
        background = SettingsDialogModeBackground,
        enabled = enabled,
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
        Text(label, color = SettingsDialogForeground)
        TuiDropdownTrigger(
            dropdownState = dropdownState,
            label = selectedLabel,
            modifier = Modifier.fillMaxWidth().background(background),
            color = SettingsDialogForeground,
            enabled = enabled,
        )
    }
}

@Composable
private fun BoxScope.AgentSettingsDropdownMenus(
    settings: io.github.stream29.kodex.openai.KodexAgentSettings,
    models: List<OpenAiModelId>,
    dropdowns: SettingsDropdownStates,
    enabled: Boolean,
    onUpdate: ((io.github.stream29.kodex.openai.KodexAgentSettings) -> io.github.stream29.kodex.openai.KodexAgentSettings) -> Unit,
) {
    TuiDropdownMenu(
        dropdownState = dropdowns.model,
        options = (models + settings.model).distinct(),
        selected = settings.model,
        optionLabel = OpenAiModelId::value,
        enabled = enabled,
        backgroundColor = PopupMenuBackground,
        onSelect = { model -> onUpdate { current -> current.copy(model = model) } },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.reasoning,
        options = knownReasoningEfforts,
        selected = settings.reasoning.effort,
        optionLabel = ReasoningEffort::displayName,
        enabled = enabled,
        backgroundColor = PopupMenuBackground,
        onSelect = { effort -> onUpdate { current -> current.copy(reasoning = current.reasoning.copy(effort = effort)) } },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.serviceTier,
        options = ServiceTier.entries.toList(),
        selected = settings.serviceTier,
        optionLabel = ServiceTier::displayName,
        enabled = enabled,
        backgroundColor = PopupMenuBackground,
        onSelect = { tier -> onUpdate { current -> current.copy(serviceTier = tier) } },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.mode,
        options = ModeKind.entries.toList(),
        selected = settings.collaborationMode,
        optionLabel = ModeKind::displayName,
        enabled = enabled,
        backgroundColor = PopupMenuBackground,
        onSelect = { mode -> onUpdate { current -> current.copy(collaborationMode = mode) } },
    )
}

@Composable
private fun BoxScope.NewSessionSettingsDropdownMenus(
    settings: io.github.stream29.kodex.cli.settings.KodexNewSessionSettings,
    models: List<OpenAiModelId>,
    dropdowns: SettingsDropdownStates,
    onUpdate: ((io.github.stream29.kodex.cli.settings.KodexNewSessionSettings) -> io.github.stream29.kodex.cli.settings.KodexNewSessionSettings) -> Unit,
) {
    TuiDropdownMenu(
        dropdownState = dropdowns.model,
        options = (models + settings.model).distinct(),
        selected = settings.model,
        optionLabel = OpenAiModelId::value,
        backgroundColor = PopupMenuBackground,
        onSelect = { model -> onUpdate { current -> current.copy(model = model) } },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.reasoning,
        options = knownReasoningEfforts,
        selected = settings.reasoningEffort,
        optionLabel = ReasoningEffort::displayName,
        backgroundColor = PopupMenuBackground,
        onSelect = { effort -> onUpdate { current -> current.copy(reasoningEffort = effort) } },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.serviceTier,
        options = ServiceTier.entries.toList(),
        selected = settings.serviceTier,
        optionLabel = ServiceTier::displayName,
        backgroundColor = PopupMenuBackground,
        onSelect = { tier -> onUpdate { current -> current.copy(serviceTier = tier) } },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.mode,
        options = ModeKind.entries.toList(),
        selected = settings.mode,
        optionLabel = ModeKind::displayName,
        backgroundColor = PopupMenuBackground,
        onSelect = { mode -> onUpdate { current -> current.copy(mode = mode) } },
    )
}

@Composable
private fun BoxScope.SessionTitleSettingsDropdownMenus(
    settings: SessionTitleSettings,
    models: List<OpenAiModelId>,
    dropdowns: SettingsDropdownStates,
    onUpdate: ((SessionTitleSettings) -> SessionTitleSettings) -> Unit,
) {
    TuiDropdownMenu(
        dropdownState = dropdowns.model,
        options = (models + listOfNotNull(settings.model, DefaultSessionTitleModel)).distinct(),
        selected = settings.model ?: DefaultSessionTitleModel,
        optionLabel = OpenAiModelId::value,
        backgroundColor = PopupMenuBackground,
        onSelect = { model -> onUpdate { current -> current.copy(model = model) } },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.reasoning,
        options = knownReasoningEfforts,
        selected = settings.reasoningEffort,
        optionLabel = ReasoningEffort::displayName,
        backgroundColor = PopupMenuBackground,
        onSelect = { effort -> onUpdate { current -> current.copy(reasoningEffort = effort) } },
    )
}

internal class SettingsDropdownStates(
    val model: TuiDropdownState,
    val reasoning: TuiDropdownState,
    val serviceTier: TuiDropdownState,
    val mode: TuiDropdownState,
) {
    fun dismissAll() {
        model.dismiss()
        reasoning.dismiss()
        serviceTier.dismiss()
        mode.dismiss()
    }
}

private data class WorkingDirectoryPickerRequest(
    val initialDirectory: Path,
    val target: WorkingDirectoryTarget,
)

private sealed interface WorkingDirectoryTarget {
    data class Agent(val viewModel: AgentRuntimeViewModel) : WorkingDirectoryTarget

    data class NewSession(val viewModel: NewSessionViewModel) : WorkingDirectoryTarget
}

@Composable
private fun <T> SettingsChoiceGroup(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    background: Color,
    enabled: Boolean,
    vertical: Boolean = false,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(background)) {
        Text(label, color = SettingsDialogForeground)
        if (vertical) {
            options.forEach { option ->
                TuiButton(
                    label = optionLabel(option),
                    modifier = Modifier.fillMaxWidth().background(
                        if (option == selected) SettingsDialogSelectionBackground else background,
                    ),
                    color = SettingsDialogForeground,
                    enabled = enabled,
                    onClick = { onSelect(option) },
                )
            }
        } else {
            Row {
                options.forEachIndexed { index, option ->
                    if (index != 0) Text(" ")
                    TuiButton(
                        label = optionLabel(option),
                        modifier = Modifier.background(
                            if (option == selected) SettingsDialogSelectionBackground else background,
                        ),
                        color = SettingsDialogForeground,
                        enabled = enabled,
                        onClick = { onSelect(option) },
                    )
                }
            }
        }
    }
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

private const val SessionTabBarRows: Int = 1
