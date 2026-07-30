package io.github.stream29.codex.lite.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.oshai.kotlinlogging.KotlinLogging
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.fillMaxHeight
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.IntrinsicSize
import com.jakewharton.mosaic.layout.onPointerHover
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.codex.lite.cli.agent.AgentRuntimeViewState
import io.github.stream29.codex.lite.cli.agent.label
import io.github.stream29.codex.lite.cli.agent.toRenderState
import io.github.stream29.codex.lite.cli.components.TuiButton
import io.github.stream29.codex.lite.cli.components.safeSpringInt
import io.github.stream29.codex.lite.cli.components.TuiPressable
import io.github.stream29.codex.lite.cli.components.TuiDialog
import io.github.stream29.codex.lite.cli.components.LazyColumn
import io.github.stream29.codex.lite.cli.components.LazyListState
import io.github.stream29.codex.lite.cli.components.TuiDropdownMenu
import io.github.stream29.codex.lite.cli.components.TuiDropdownState
import io.github.stream29.codex.lite.cli.components.TuiDropdownTrigger
import io.github.stream29.codex.lite.cli.components.items
import io.github.stream29.codex.lite.cli.components.rememberTuiDropdownState
import io.github.stream29.codex.lite.cli.components.TuiPopupMenu
import io.github.stream29.codex.lite.cli.components.TuiPopupMenuItem
import io.github.stream29.codex.lite.cli.components.TuiPopupSubmenuItem
import io.github.stream29.codex.lite.cli.components.TextInput
import io.github.stream29.codex.lite.cli.components.TextInputLayout
import io.github.stream29.codex.lite.cli.components.TextInputState
import io.github.stream29.codex.lite.cli.components.TextInputValue
import io.github.stream29.codex.lite.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.codex.lite.cli.components.rememberTuiPopupAnchor
import io.github.stream29.codex.lite.cli.session.RootSessionEntry
import io.github.stream29.codex.lite.cli.session.RootSessionViewState
import io.github.stream29.codex.lite.cli.components.TuiPopupHost
import io.github.stream29.codex.lite.cli.newsession.NewSessionViewModel
import io.github.stream29.codex.lite.cli.newsession.NewSessionViewState
import io.github.stream29.codex.lite.cli.pathpicker.DirectoryPickerPopup
import io.github.stream29.codex.lite.cli.settings.login.OpenAiLoginPopup
import io.github.stream29.codex.lite.cli.settings.login.OpenAiLoginViewModel
import io.github.stream29.codex.lite.cli.settings.login.OpenAiLoginViewModel as createOpenAiLoginViewModel
import io.github.stream29.codex.lite.cli.settings.CodexAuthSource
import io.github.stream29.codex.lite.cli.settings.CodexGlobalSettings
import io.github.stream29.codex.lite.cli.settings.CodexNewSessionSettings
import io.github.stream29.codex.lite.cli.settings.NewLineKey
import io.github.stream29.codex.lite.cli.settings.SubmitKey
import io.github.stream29.codex.lite.openai.ModeKind
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.ReasoningEffort
import io.github.stream29.codex.lite.openai.ServiceTier
import io.github.stream29.codex.lite.utils.terminaltext.terminalCellWidth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

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
    val agentState by (selectedAgent?.viewModel?.state ?: rememberEmptyAgentState()).collectAsState()
    val scope = rememberCoroutineScope()
    // Keep the final column and row unused. This matches the terminal-safe bounds used by the
    // existing Mosaic screen and avoids a trailing cursor movement scrolling the frame.
    val columns = (terminal.size.columns - 1).coerceAtLeast(1)
    val rows = (terminal.size.rows - 1).coerceAtLeast(1)
    var sidebarPinnedExpanded by remember { mutableStateOf(false) }
    var sidebarHovered by remember { mutableStateOf(false) }
    val sidebarExpanded = sidebarPinnedExpanded || sidebarHovered
    val sidebarColumns = safeSpringInt(
        targetValue = if (sidebarExpanded) SessionSidebarExpandedColumns else SessionSidebarCollapsedColumns,
        minimum = SessionSidebarCollapsedColumns,
        maximum = SessionSidebarExpandedColumns,
        label = "agent sidebar width",
    )
    val contentColumns = (columns - sidebarColumns).coerceAtLeast(1)
    val contentRows = (rows - SessionTabBarRows).coerceAtLeast(0)
    val modelDropdown = rememberTuiDropdownState()
    val tierDropdown = rememberTuiDropdownState()
    val modeDropdown = rememberTuiDropdownState()
    val tabMenuAnchor = rememberTuiPopupAnchor()
    var browserOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsRoute by remember { mutableStateOf(SettingsRoute.Global) }
    var openAiLoginViewModel by remember { mutableStateOf<OpenAiLoginViewModel?>(null) }
    var directoryPickerInitialDirectory by remember { mutableStateOf<Path?>(null) }
    val settingsDropdowns = SettingsDropdownStates(
        model = rememberTuiDropdownState(),
        reasoning = rememberTuiDropdownState(),
        serviceTier = rememberTuiDropdownState(),
        mode = rememberTuiDropdownState(),
    )
    var tabMenuTarget by remember { mutableStateOf<SessionTabTarget?>(null) }
    var renameSessionRequest by remember { mutableStateOf<RenameSessionRequest?>(null) }
    val newSessionSettings = activeNewSessionState?.settings ?: globalSettings.newSession
    val modelOptions = (
        applicationState.models.map { model -> model.slug } +
            newSessionSettings.model +
            listOfNotNull(agentState?.durable?.settings?.model)
        )
        .distinct()
    val activeSettings = agentState?.durable?.settings
    val selectedModel = activeSettings?.model ?: newSessionSettings.model
    val selectedReasoning = activeSettings?.reasoning?.effort ?: newSessionSettings.reasoningEffort
    val selectedTier = activeSettings?.serviceTier ?: newSessionSettings.serviceTier
    val selectedMode = activeSettings?.collaborationMode ?: newSessionSettings.mode
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
    val forkEnabled = applicationState.activeTab is SessionTabTarget.OpenSession &&
        selectedRoot?.viewModel?.state?.value?.let { state -> !state.running && state.latestIndex >= 0 } == true

    val failureAgentState = agentState
    LaunchedEffect(failureAgentState?.failureStackTrace) {
        failureAgentState?.failureStackTrace?.let { stackTrace ->
            KotlinLogging.logger {}
                .error { "Agent runtime operation failed for ${failureAgentState.agentId}\n$stackTrace" }
        }
    }
    LaunchedEffect(applicationState.activeTab) {
        if (tabMenuTarget != applicationState.activeTab) tabMenuTarget = null
        if (renameSessionRequest?.target != applicationState.activeTab) renameSessionRequest = null
    }

    Box(modifier = Modifier.width(columns).height(rows)) {
        TuiPopupHost(modifier = Modifier.width(columns).height(rows)) {
            Column(modifier = Modifier.width(columns).height(rows)) {
                SessionTabBar(
                    tabs = applicationState.tabs,
                    columns = columns,
                    tabMenuAnchor = tabMenuAnchor,
                    onSelectTab = { target ->
                        if (target == applicationState.activeTab) {
                            tabMenuTarget = target
                        } else {
                            tabMenuTarget = null
                            scope.launch { viewModel.selectTab(target) }
                        }
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
                        onSelectAgent = viewModel::selectAgent,
                    )
                    if (activeNewSession != null) {
                        val newSessionTarget = requireNotNull(
                            applicationState.activeTab as? SessionTabTarget.NewSession,
                        )
                        NewSessionScreen(
                            composerViewModel = activeNewSession.composer,
                            columns = contentColumns,
                            rows = contentRows,
                            newLineKey = globalSettings.newLineKey,
                            onSubmit = { viewModel.submitNewSessionComposer(newSessionTarget) },
                            statusBar = {
                                SessionTreeStatusBar(
                                    columns = contentColumns,
                                    agentState = null,
                                    newSessionSettings = newSessionSettings,
                                    notice = activeNewSessionState?.failureMessage,
                                    modelDropdown = modelDropdown,
                                    tierDropdown = tierDropdown,
                                    modeDropdown = modeDropdown,
                                    onCancel = {},
                                    showFork = false,
                                    forkEnabled = false,
                                    onFork = {},
                                    onOpenSettings = {
                                        settingsRoute = SettingsRoute.Global
                                        settingsOpen = true
                                    },
                                )
                            },
                        )
                    } else if (selectedAgent != null) {
                        AgentRuntimeScreen(
                            agent = selectedAgent,
                            columns = contentColumns,
                            rows = contentRows,
                            newLineKey = globalSettings.newLineKey,
                            statusBar = { runtimeState ->
                                SessionTreeStatusBar(
                                    columns = contentColumns,
                                    agentState = runtimeState,
                                    newSessionSettings = globalSettings.newSession,
                                    notice = null,
                                    modelDropdown = modelDropdown,
                                    tierDropdown = tierDropdown,
                                    modeDropdown = modeDropdown,
                                    onCancel = { selectedAgent.viewModel.cancel() },
                                    showFork = true,
                                    forkEnabled = forkEnabled,
                                    onFork = { scope.launch { runCatching { viewModel.forkSelectedSession() } } },
                                    onOpenSettings = {
                                        settingsRoute = SettingsRoute.Global
                                        settingsOpen = true
                                    },
                                )
                            },
                        )
                    } else {
                        Box(modifier = Modifier.width(contentColumns).height(contentRows)) {
                            Text("Opening session…", textStyle = TextStyle.Dim)
                        }
                    }
                }
            }
            val openTabMenuTarget = tabMenuTarget
            if (openTabMenuTarget != null && openTabMenuTarget == applicationState.activeTab) {
                TuiPopupMenu(
                    expanded = true,
                    anchor = tabMenuAnchor,
                    onDismissRequest = { tabMenuTarget = null },
                    backgroundColor = PopupMenuBackground,
                ) {
                    TuiPopupMenuItem(key = "rename-session", onClick = {
                        tabMenuTarget = null
                        renameSessionRequest = RenameSessionRequest(
                            target = openTabMenuTarget,
                            initialName = activeSessionName,
                        )
                    }) { Text("Rename") }
                    TuiPopupMenuItem(key = "close-tab", onClick = {
                        tabMenuTarget = null
                        scope.launch { viewModel.closeTab(openTabMenuTarget) }
                    }) { Text("Close session") }
                }
            }
            TuiDropdownMenu(
                dropdownState = modelDropdown,
                backgroundColor = PopupMenuBackground,
            ) {
                    modelOptions.forEach { model ->
                        val efforts = applicationState.models
                            .firstOrNull { info -> info.slug == model }
                            ?.supportedReasoningLevels
                            ?.map { preset -> preset.effort }
                            .orEmpty()
                            .ifEmpty { listOf(selectedReasoning) }
                        if (efforts.size == 1) {
                            val effort = efforts.single()
                            TuiPopupMenuItem(key = model, selected = model == selectedModel, onClick = {
                                scope.launch {
                                    when {
                                        selectedAgent != null -> selectedAgent.viewModel.updateSettings { current ->
                                            current.copy(model = model, reasoning = current.reasoning.copy(effort = effort))
                                        }

                                        activeNewSession != null -> viewModel.updateNewSessionSettings { current ->
                                            current.copy(model = model, reasoningEffort = effort)
                                        }
                                    }
                                }
                            }) { Text(model.value) }
                        } else {
                            TuiPopupSubmenuItem(
                                key = model,
                                selected = model == selectedModel,
                                initialSubmenuFocusedKey = selectedReasoning,
                                submenuContent = {
                                    efforts.forEach { effort ->
                                        TuiPopupMenuItem(
                                            key = effort,
                                            selected = model == selectedModel && effort == selectedReasoning,
                                            onClick = {
                                                scope.launch {
                                                    when {
                                                        selectedAgent != null -> selectedAgent.viewModel.updateSettings { current ->
                                                            current.copy(
                                                                model = model,
                                                                reasoning = current.reasoning.copy(effort = effort),
                                                            )
                                                        }

                                                        activeNewSession != null -> viewModel.updateNewSessionSettings { current ->
                                                            current.copy(model = model, reasoningEffort = effort)
                                                        }
                                                    }
                                                }
                                            },
                                        ) { Text(effort.displayName()) }
                                    }
                                },
                            ) { Text(model.value) }
                        }
                    }
            }
            TuiDropdownMenu(
                dropdownState = tierDropdown,
                options = ServiceTier.entries.toList(),
                selected = selectedTier,
                optionLabel = ServiceTier::displayName,
                backgroundColor = PopupMenuBackground,
                onSelect = { tier ->
                    scope.launch {
                        when {
                            selectedAgent != null -> selectedAgent.viewModel.updateSettings { current ->
                                current.copy(serviceTier = tier)
                            }

                            activeNewSession != null -> viewModel.updateNewSessionSettings { current ->
                                current.copy(serviceTier = tier)
                            }
                        }
                    }
                },
            )
            TuiDropdownMenu(
                dropdownState = modeDropdown,
                options = ModeKind.entries.toList(),
                selected = selectedMode,
                optionLabel = { mode -> "${mode.displayName()} mode" },
                backgroundColor = PopupMenuBackground,
                onSelect = { mode ->
                    scope.launch {
                        when {
                            selectedAgent != null -> selectedAgent.viewModel.updateSettings { current ->
                                current.copy(collaborationMode = mode)
                            }

                            activeNewSession != null -> viewModel.updateNewSessionSettings { current ->
                                current.copy(mode = mode)
                            }
                        }
                    }
                },
            )
            if (browserOpen) {
                SessionTreeBrowserDialog(
                    viewModel = viewModel,
                    state = applicationState,
                    scope = scope,
                    onDismiss = { browserOpen = false },
                )
            }
            if (settingsOpen) {
                GlobalSettingsDialog(
                    state = globalSettings,
                    agent = selectedAgent?.viewModel,
                    agentState = agentState,
                    models = modelOptions,
                    route = settingsRoute,
                    onRouteSelected = { settingsRoute = it },
                    dropdowns = settingsDropdowns,
                    onDismiss = {
                        directoryPickerInitialDirectory = null
                        openAiLoginViewModel = null
                        settingsDropdowns.dismissAll()
                        settingsRoute = SettingsRoute.Global
                        settingsOpen = false
                    },
                    onNewLineKey = { key -> scope.launch { viewModel.updateNewLineKey(key) } },
                    onAuthSource = { source -> scope.launch { viewModel.updateAuthSource(source) } },
                    onOpenLogin = {
                        if (openAiLoginViewModel == null) {
                            openAiLoginViewModel = scope.createOpenAiLoginViewModel(viewModel.authStore)
                        }
                    },
                    onBrowseWorkingDirectory = { directory -> directoryPickerInitialDirectory = directory },
                    newSessionSettings = newSessionSettings,
                    sessionName = activeSessionName,
                    onRenameSession = {
                        directoryPickerInitialDirectory = null
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
                    SettingsRoute.Global -> Unit
                    SettingsRoute.Session -> activeSettings?.let { settings ->
                        AgentSettingsDropdownMenus(
                            settings = settings,
                            models = modelOptions,
                            dropdowns = settingsDropdowns,
                            enabled = agentState?.running != true,
                            onUpdate = { transform ->
                                scope.launch { selectedAgent?.viewModel?.updateSettings(transform) }
                            },
                        )
                    }
                    SettingsRoute.NewSession -> NewSessionSettingsDropdownMenus(
                        settings = newSessionSettings,
                        models = modelOptions,
                        dropdowns = settingsDropdowns,
                        onUpdate = { transform ->
                            scope.launch {
                                if (activeNewSession != null) {
                                    viewModel.updateNewSessionSettings(transform)
                                } else {
                                    viewModel.updateNewSessionDefaults(transform)
                                }
                            }
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
            directoryPickerInitialDirectory?.let { initialDirectory ->
                DirectoryPickerPopup(
                    initialDirectory = initialDirectory,
                    onDismissRequest = { directoryPickerInitialDirectory = null },
                    onDirectorySelected = { directory ->
                        directoryPickerInitialDirectory = null
                        scope.launch {
                            selectedAgent?.viewModel?.updateSettings { current -> current.copy(cwd = directory) }
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

@Composable
private fun collectNewSessionState(viewModel: NewSessionViewModel?): NewSessionViewState? {
    if (viewModel == null) return null
    val state by viewModel.state.collectAsState()
    return state
}

@Composable
private fun SessionAgentSidebar(
    tree: RootSessionViewState?,
    expanded: Boolean,
    columns: Int,
    rows: Int,
    onHoverChanged: (Boolean) -> Unit,
    onToggleExpanded: () -> Unit,
    onSelectAgent: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(columns)
            .height(rows)
            .background(SettingsDialogNavigationBackground)
            .onPointerHover(
                onPointerEnter = { onHoverChanged(true) },
                onPointerExit = { onHoverChanged(false) },
            ),
    ) {
        if (expanded) {
            TuiButton(
                label = "←",
                modifier = Modifier.fillMaxWidth().background(SettingsDialogHeaderBackground),
                color = SettingsDialogForeground,
                onClick = onToggleExpanded,
            )
            Text("Agent tree", color = SettingsDialogForeground)
            Box(modifier = Modifier.width(columns).height((rows - 2).coerceAtLeast(0))) {
                val agents = tree?.agents.orEmpty()
                if (agents.isEmpty()) {
                    Text("No agents", color = SettingsDialogForeground)
                } else {
                    LazyColumn(modifier = Modifier.width(columns).height((rows - 2).coerceAtLeast(0))) {
                        items(agents, key = { it.agentId }) { agent ->
                            val agentState by agent.viewModel.state.collectAsState()
                            val background = if (agent.selected) {
                                SettingsDialogSelectionBackground
                            } else {
                                SettingsDialogNavigationBackground
                            }
                            val label = buildString {
                                append("  ".repeat(agent.depth))
                                append(if (agent.selected) "> " else "- ")
                                append(agentState.durable.settings?.threadName ?: agent.agentId)
                                if (agentState.running) append(" *")
                            }.ellipsizeToTerminalWidth(columns)
                            Column(modifier = Modifier.fillMaxWidth().background(background)) {
                                TuiButton(
                                    label = label,
                                    modifier = Modifier.fillMaxWidth().background(background),
                                    color = SettingsDialogForeground,
                                    onClick = { onSelectAgent(agent.agentId) },
                                )
                                Text(
                                    value = (
                                        "  ".repeat(agent.depth + 1) + agentState.toRenderState().label()
                                    ).ellipsizeToTerminalWidth(columns),
                                    modifier = Modifier.fillMaxWidth().background(background),
                                    color = SettingsDialogForeground,
                                    textStyle = TextStyle.Dim,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            TuiPressable(
                onClick = onToggleExpanded,
                modifier = Modifier.fillMaxWidth().background(SettingsDialogHeaderBackground),
            ) { _, isHovered, _ ->
                Text(
                    value = "→",
                    color = SettingsDialogForeground,
                    textStyle = if (isHovered) TextStyle.Bold else TextStyle.Unspecified,
                )
            }
        }
    }
}

@Composable
private fun SessionTreeStatusBar(
    columns: Int,
    agentState: AgentRuntimeViewState?,
    newSessionSettings: CodexNewSessionSettings,
    notice: String?,
    modelDropdown: TuiDropdownState,
    tierDropdown: TuiDropdownState,
    modeDropdown: TuiDropdownState,
    onCancel: () -> Unit,
    showFork: Boolean,
    forkEnabled: Boolean,
    onFork: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val settings = agentState?.durable?.settings
    val model = settings?.model ?: newSessionSettings.model
    val reasoning = settings?.reasoning?.effort ?: newSessionSettings.reasoningEffort
    val tier = settings?.serviceTier ?: newSessionSettings.serviceTier
    val mode = settings?.collaborationMode ?: newSessionSettings.mode
    Row(modifier = Modifier.width((columns - 1).coerceAtLeast(1))) {
        agentState?.durable?.tokenCount?.let { tokenCount -> Text("${tokenCount}t ") }
        if (agentState?.running == true) {
            TuiButton(label = "Stop", onClick = onCancel)
            Text(" ")
        }
        TuiDropdownTrigger(
            dropdownState = modelDropdown,
            label = "${model.value} ${reasoning.displayName()}",
            modifier = Modifier.background(SessionButtonBackground),
            color = SessionForeground,
        )
        Text(" ")
        TuiDropdownTrigger(
            dropdownState = tierDropdown,
            label = "tier: ${tier.displayName()}",
            modifier = Modifier.background(SessionButtonBackground),
            color = SessionForeground,
        )
        Text(" ")
        TuiDropdownTrigger(
            dropdownState = modeDropdown,
            label = "${mode.displayName()} mode",
            modifier = Modifier.background(SessionButtonBackground),
            color = SessionForeground,
        )
        Text(" ")
        TuiButton(
            label = "Settings",
            modifier = Modifier.background(SessionButtonBackground),
            color = SessionForeground,
            onClick = onOpenSettings,
        )
        if (showFork) {
            Text(" ")
            TuiButton(
                label = "Fork",
                enabled = forkEnabled,
                modifier = Modifier.background(SessionButtonBackground),
                color = SessionForeground,
                onClick = onFork,
            )
        }
        notice?.let { failure -> Text(" [notice] $failure") }
        agentState?.failureMessage?.let { failure -> Text(" [error] $failure") }
    }
}

@Composable
private fun BoxScope.SessionTreeBrowserDialog(
    viewModel: SessionTreeCliViewModel,
    state: SessionTreeCliState,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, 84)
    val bodyRows = sessionBrowserVisibleRows(LocalTerminalState.current.size.rows)
    val selectedSessionIndex = (state.activeTab as? SessionTabTarget.OpenSession)?.sessionIndex
    val selectedSessionPosition = state.sessions.sessions.indexOfFirst { entry ->
        entry.sessionIndex == selectedSessionIndex
    }.coerceAtLeast(0)
    val sessionListState = remember(state.sessions.sessions.map { it.sessionIndex }, selectedSessionPosition, bodyRows) {
        LazyListState(initialFirstVisibleItemIndex = initialBrowserScrollOffset(
            state.sessions.sessions.size, bodyRows, selectedSessionPosition,
        ))
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
                            TuiButton(
                                label = session.sessionBrowserLabel((width - 2).coerceAtLeast(1)),
                                color = SettingsDialogForeground,
                                autoFocus = session.sessionIndex == selectedSessionIndex,
                                modifier = if (session.sessionIndex == selectedSessionIndex) {
                                    Modifier.background(SettingsDialogSelectionBackground)
                                } else {
                                    Modifier
                                },
                                onClick = {
                                    scope.launch {
                                        viewModel.open(session.sessionIndex)
                                        onDismiss()
                                    }
                                },
                            )
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

private fun initialBrowserScrollOffset(itemCount: Int, visibleItemCount: Int, selectedIndex: Int): Int =
    (selectedIndex - visibleItemCount / 2).coerceIn(0, (itemCount - visibleItemCount).coerceAtLeast(0))

internal fun sessionBrowserVisibleRows(terminalRows: Int): Int =
    (terminalRows - SessionBrowserDialogRows).coerceIn(1, SessionBrowserMaximumListRows)

internal fun RootSessionEntry.sessionBrowserLabel(maximumColumns: Int): String =
    (threadName?.takeIf(String::isNotBlank) ?: "Session $sessionIndex")
        .ellipsizeToTerminalWidth(maximumColumns)

private const val SessionBrowserMaximumListRows: Int = 16
private const val SessionBrowserDialogRows: Int = 2
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
    state: CodexGlobalSettings,
    agent: io.github.stream29.codex.lite.cli.agent.AgentRuntimeViewModel?,
    agentState: AgentRuntimeViewState?,
    models: List<OpenAiModelId>,
    route: SettingsRoute,
    onRouteSelected: (SettingsRoute) -> Unit,
    dropdowns: SettingsDropdownStates,
    onDismiss: () -> Unit,
    onNewLineKey: (NewLineKey) -> Unit,
    onAuthSource: (CodexAuthSource) -> Unit,
    onOpenLogin: () -> Unit,
    onBrowseWorkingDirectory: (Path) -> Unit,
    newSessionSettings: CodexNewSessionSettings,
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
                            onNewLineKey = onNewLineKey,
                            onAuthSource = onAuthSource,
                            onOpenLogin = onOpenLogin,
                        )
                        SettingsRoute.Session -> {
                            sessionName?.let { name ->
                                SessionNameSettingsContent(name = name, onRename = onRenameSession)
                            }
                            if (configuration == null || agent == null) {
                                Text(
                                    value = "No selected session",
                                    modifier = Modifier.fillMaxWidth().background(SettingsDialogHomeBackground),
                                    color = SettingsDialogForeground,
                                )
                            } else {
                                AgentSettingsContent(
                                    settings = configuration,
                                    models = models,
                                    dropdowns = dropdowns,
                                    enabled = !agentState.running,
                                    onBrowseWorkingDirectory = onBrowseWorkingDirectory,
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
    state: CodexGlobalSettings,
    onNewLineKey: (NewLineKey) -> Unit,
    onAuthSource: (CodexAuthSource) -> Unit,
    onOpenLogin: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogHomeBackground)) {
        Text("Codex home", color = SettingsDialogForeground)
        Text(state.codexHome.toString(), modifier = Modifier.fillMaxWidth(), color = SettingsDialogForeground)
    }
    SettingsChoiceGroup(
        label = "Authentication",
        options = CodexAuthSource.entries.toList(),
        selected = state.authSource,
        optionLabel = CodexAuthSource::dialogLabel,
        background = SettingsDialogHomeBackground,
        enabled = true,
        onSelect = onAuthSource,
    )
    Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogHomeBackground)) {
        Text("Codex Lite account", color = SettingsDialogForeground)
        TuiButton(
            label = "Sign in",
            modifier = Modifier.background(SettingsDialogHomeBackground),
            color = SettingsDialogForeground,
            onClick = onOpenLogin,
        )
    }
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
    settings: io.github.stream29.codex.lite.openai.CodexAgentSettings,
    models: List<OpenAiModelId>,
    dropdowns: SettingsDropdownStates,
    enabled: Boolean,
    onBrowseWorkingDirectory: (Path) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogHomeBackground)) {
        Text("Working directory", color = SettingsDialogForeground)
        Text(
            value = settings.cwd.toString(),
            modifier = Modifier.fillMaxWidth(),
            color = SettingsDialogForeground,
        )
        TuiButton(
            label = "Browse",
            modifier = Modifier.background(SettingsDialogHomeBackground),
            color = SettingsDialogForeground,
            enabled = enabled,
            onClick = { onBrowseWorkingDirectory(settings.cwd) },
        )
    }
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
private fun NewSessionSettingsContent(
    settings: io.github.stream29.codex.lite.cli.settings.CodexNewSessionSettings,
    dropdowns: SettingsDropdownStates,
) {
    SettingsDropdownField(
        label = "Model",
        selectedLabel = settings.model.value,
        dropdownState = dropdowns.model,
        background = SettingsDialogHomeBackground,
    )
    SettingsDropdownField(
        label = "Reasoning",
        selectedLabel = settings.reasoningEffort.displayName(),
        dropdownState = dropdowns.reasoning,
        background = SettingsDialogNewLineBackground,
    )
    SettingsDropdownField(
        label = "Service tier",
        selectedLabel = settings.serviceTier.displayName(),
        dropdownState = dropdowns.serviceTier,
        background = SettingsDialogSubmitKeyBackground,
    )
    SettingsDropdownField(
        label = "Mode",
        selectedLabel = settings.mode.displayName(),
        dropdownState = dropdowns.mode,
        background = SettingsDialogModeBackground,
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
    settings: io.github.stream29.codex.lite.openai.CodexAgentSettings,
    models: List<OpenAiModelId>,
    dropdowns: SettingsDropdownStates,
    enabled: Boolean,
    onUpdate: ((io.github.stream29.codex.lite.openai.CodexAgentSettings) -> io.github.stream29.codex.lite.openai.CodexAgentSettings) -> Unit,
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
    settings: io.github.stream29.codex.lite.cli.settings.CodexNewSessionSettings,
    models: List<OpenAiModelId>,
    dropdowns: SettingsDropdownStates,
    onUpdate: ((io.github.stream29.codex.lite.cli.settings.CodexNewSessionSettings) -> io.github.stream29.codex.lite.cli.settings.CodexNewSessionSettings) -> Unit,
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

private class SettingsDropdownStates(
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

private fun CodexAuthSource.dialogLabel(): String = when (this) {
    CodexAuthSource.Codex -> "Codex"
    CodexAuthSource.CodexLite -> "Codex Lite"
}

@Composable
private fun rememberEmptyAgentState() = remember {
    kotlinx.coroutines.flow.MutableStateFlow<AgentRuntimeViewState?>(null)
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

private const val SessionSidebarExpandedColumns: Int = 28
private const val SessionSidebarCollapsedColumns: Int = 1
private const val SessionTabBarRows: Int = 1
