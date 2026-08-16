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
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.AgentHistoryTarget
import io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState
import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.history.contract.AgentHistoryWindowSnapshot
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiContextMenu
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.items
import io.github.stream29.kodex.cli.pathpicker.DirectoryPickerPopup
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.OpenAiLoginPopup
import io.github.stream29.kodex.cli.settings.SettingsPopup
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModelInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Application shell; all mutable child state is collected by its exact renderer. */
@Composable
public fun SessionTreeCliScreen(
    viewModel: ApplicationViewModel,
    newLineKey: StateFlow<NewLineKey>,
) {
    val terminal = LocalTerminalState.current
    val navigation by viewModel.navigation.collectAsState()
    val popup by viewModel.popup.collectAsState()
    val currentNewLineKey by newLineKey.collectAsState()
    val historyStates = rememberAgentHistoryUiStateRegistry(navigation.tabs)
    val tabStates = collectSessionTabRenderStates(navigation.tabs, navigation.selectedIndex)
    val sessionSummary = summarizeOpenSessions(tabStates)
    val runningFrame = rememberRunningIndicatorFrame(
        active = sessionSummary.runningSessionCount > 0,
    )
    TerminalTitleEffect(sessionSummary.sessionCount, sessionSummary.runningSessionCount)

    val columns = (terminal.size.columns - 1).coerceAtLeast(1)
    val rows = (terminal.size.rows - 1).coerceAtLeast(1)
    val scope = rememberCoroutineScope()
    var sidebarPinnedExpanded by remember { mutableStateOf(false) }
    var sidebarHovered by remember { mutableStateOf(false) }
    var shellSessionMenu by remember { mutableStateOf<ShellSessionMenuRequest?>(null) }
    var tabMenu by remember { mutableStateOf<SessionTabMenuRequest?>(null) }
    var historyMenu by remember { mutableStateOf<HistoryEntryMenuRequest?>(null) }
    val sidebarExpanded =
        sidebarPinnedExpanded || sidebarHovered || shellSessionMenu != null
    val sidebarColumns = if (sidebarExpanded) SessionSidebarExpandedColumns else 0
    val contentColumns = (columns - sidebarColumns).coerceAtLeast(1)
    val contentRows = (rows - SessionTabBarRows).coerceAtLeast(0)
    val selected = navigation.selected
    val selectedPersisted = selected as? PersistedSessionViewModel
    val selectedAgent = collectSelectedAgent(selectedPersisted)
    val topology = collectTopology(selectedPersisted)
    val settingsOwner: AgentSettingsViewModel? =
        selectedAgent ?: (selected as? NewSessionViewModel)
    val runtimeDropdowns = RuntimeConfigurationDropdowns.remember(settingsOwner)
    val runtimeSettings = collectRuntimeSettings(settingsOwner)
    val runtimeModels = collectRuntimeModels(settingsOwner)

    TuiTheme {
        TuiPopupHost {
            Column(modifier = Modifier.width(columns).height(rows)) {
                SessionTabBar(
                    tabs = tabStates,
                    runningIndicatorFrame = runningFrame,
                    columns = columns,
                    onSelectTab = { target ->
                        historyMenu = null
                        val index = navigation.tabs.indexOfFirst { child -> child === target }
                        if (index >= 0) scope.launch { viewModel.selectTab(index) }
                    },
                    onOpenTabMenu = { target, name, anchor, position ->
                        shellSessionMenu = null
                        historyMenu = null
                        tabMenu = SessionTabMenuRequest(target, name, anchor, position)
                    },
                    onCreateNewSession = {
                        historyMenu = null
                        scope.launch { viewModel.createNewSessionTab() }
                    },
                    onOpenSessions = {
                        historyMenu = null
                        scope.launch { viewModel.openSessionCatalogPopup() }
                    },
                )
                Box(modifier = Modifier.width(columns).height(contentRows)) {
                    Row(modifier = Modifier.width(columns).height(contentRows)) {
                        if (sidebarExpanded) {
                            SessionAgentSidebar(
                                topology = topology,
                                selectedAgent = selectedAgent,
                                columns = sidebarColumns,
                                rows = contentRows,
                                runningIndicatorFrame = runningFrame,
                                onHoverChanged = { sidebarHovered = it },
                                onToggleExpanded = {
                                    sidebarPinnedExpanded = !sidebarPinnedExpanded
                                    if (!sidebarPinnedExpanded) shellSessionMenu = null
                                },
                                onExpandAgent = { address ->
                                    selectedPersisted?.let { session ->
                                        scope.launch { session.materializeDirectChildren(address) }
                                    }
                                },
                                onSelectAgent = { address ->
                                    historyMenu = null
                                    selectedPersisted?.let { session ->
                                        scope.launch { session.selectAgent(address) }
                                    }
                                },
                                onOpenShellSessionMenu = { request ->
                                    tabMenu = null
                                    historyMenu = null
                                    shellSessionMenu = request
                                },
                            )
                        }
                        Box(modifier = Modifier.width(contentColumns).height(contentRows)) {
                            when (selected) {
                                is NewSessionViewModel -> NewSessionScreen(
                                    viewModel = selected,
                                    columns = contentColumns,
                                    rows = contentRows,
                                    newLineKey = currentNewLineKey,
                                    dropdowns = runtimeDropdowns,
                                    onSubmit = {
                                        val index = navigation.tabs.indexOfFirst { it === selected }
                                        if (index >= 0) {
                                            scope.launch { viewModel.materializeNewSession(index) }
                                        }
                                    },
                                    onBrowseWorkingDirectory = {
                                        scope.launch {
                                            viewModel.openWorkingDirectoryPopup(selected)
                                        }
                                    },
                                    onOpenSettings = {
                                        scope.launch {
                                            viewModel.openSettingsPopup(selected, SettingsPage.Session)
                                        }
                                    },
                                )

                                is PersistedSessionViewModel -> selectedAgent?.let { agent ->
                                    key(agent) {
                                        AgentRuntimeScreen(
                                            viewModel = agent,
                                            historyUiState = historyStates.stateFor(
                                                selected.sessionIndex,
                                                agent.address.agentId,
                                            ),
                                            columns = contentColumns,
                                            rows = contentRows,
                                            newLineKey = currentNewLineKey,
                                            dropdowns = runtimeDropdowns,
                                            onOpenHistoryEntryContextMenu = { target, anchor, position ->
                                                tabMenu = null
                                                shellSessionMenu = null
                                                historyMenu = HistoryEntryMenuRequest(
                                                    session = selected,
                                                    agent = agent,
                                                    target = target,
                                                    anchor = anchor,
                                                    clickPosition = position,
                                                )
                                            },
                                            onBrowseWorkingDirectory = {
                                                scope.launch {
                                                    viewModel.openWorkingDirectoryPopup(agent)
                                                }
                                            },
                                            onOpenSettings = {
                                                scope.launch {
                                                    viewModel.openSettingsPopup(
                                                        selected,
                                                        SettingsPage.Session,
                                                    )
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (!sidebarExpanded) {
                        SessionAgentSidebarBookmark(
                            onHoverChanged = { sidebarHovered = it },
                            onExpand = { sidebarPinnedExpanded = true },
                        )
                    }
                }
            }

            ShellSessionContextMenu(shellSessionMenu) { shellSessionMenu = null }
            SessionTabContextMenu(
                request = tabMenu,
                onDismiss = { tabMenu = null },
                onClose = { target ->
                    tabMenu = null
                    scope.launch { viewModel.closeTab(target) }
                },
                onRename = { target ->
                    tabMenu = null
                    scope.launch { viewModel.openRenameSessionPopup(target) }
                },
                onDelete = { target ->
                    tabMenu = null
                    if (target is PersistedSessionViewModel) {
                        scope.launch { viewModel.openDeleteSessionPopup(target.sessionIndex) }
                    }
                },
            )
            HistoryEntryContextMenu(
                request = historyMenu,
                selectedSession = selectedPersisted,
                selectedAgent = selectedAgent,
                onDismiss = { historyMenu = null },
                onRevert = { request ->
                    historyMenu = null
                    runCatching {
                        request.agent.requestHistoryRevert(request.target)
                    }
                },
                onFork = { request ->
                    historyMenu = null
                    scope.launch {
                        try {
                            val index = request.session.fork(request.agent, request.target)
                            viewModel.openSession(index)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Throwable) {
                            // The owning Session publishes the operation failure.
                        }
                    }
                },
            )
            AgentHistoryRevertDialog(selectedAgent)
            if (settingsOwner != null && runtimeSettings != null) {
                RuntimeConfigurationMenus(
                    viewModel = settingsOwner,
                    settings = runtimeSettings,
                    models = runtimeModels,
                    dropdowns = runtimeDropdowns,
                )
            }

            when (val open = popup) {
                ApplicationPopupState.Closed -> Unit
                is ApplicationPopupState.SessionCatalog ->
                    SessionCatalogPopup(viewModel, open)

                is ApplicationPopupState.Settings -> SettingsPopup(
                    viewModel = open.viewModel,
                    onDismissRequest = { viewModel.dismissPopup(open) },
                    onOpenLogin = {
                        scope.launch { viewModel.openLoginPopup() }
                    },
                )

                is ApplicationPopupState.RenameSession ->
                    RenameSessionPopup(viewModel, open)

                is ApplicationPopupState.DeleteSession ->
                    DeleteSessionPopup(viewModel, open)

                is ApplicationPopupState.Login -> OpenAiLoginPopup(
                    viewModel = open.viewModel,
                    onDismissRequest = { viewModel.dismissPopup(open) },
                )

                is ApplicationPopupState.WorkingDirectory -> DirectoryPickerPopup(
                    viewModel = open.viewModel.picker,
                    onDismissRequest = { viewModel.dismissPopup(open) },
                    onDirectorySelected = { directory ->
                        scope.launch {
                            open.viewModel.select(directory)
                            viewModel.dismissPopup(open)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun collectRuntimeSettings(
    viewModel: AgentSettingsViewModel?,
): KodexAgentSettings? {
    if (viewModel == null) return null
    return key(viewModel) {
        val settings by viewModel.settings.collectAsState()
        settings
    }
}

@Composable
private fun collectRuntimeModels(
    viewModel: AgentSettingsViewModel?,
): List<ModelInfo> {
    if (viewModel == null) return emptyList()
    return key(viewModel) {
        val models by viewModel.models.collectAsState()
        models
    }
}

@Composable
private fun collectSelectedAgent(
    session: PersistedSessionViewModel?,
): AgentViewModel? {
    if (session == null) return null
    return key(session) {
        val selected by session.selectedAgent.collectAsState()
        selected
    }
}

@Composable
private fun collectTopology(
    session: PersistedSessionViewModel?,
) = if (session == null) {
    null
} else {
    key(session) {
        val topology by session.topology.collectAsState()
        topology
    }
}

@Composable
private fun BoxScope.SessionCatalogPopup(
    application: ApplicationViewModel,
    open: ApplicationPopupState.SessionCatalog,
) {
    val scope = rememberCoroutineScope()
    val state by open.viewModel.state.collectAsState()
    LaunchedEffect(open) { open.viewModel.refresh() }
    TuiDialog(
        onDismissRequest = { application.dismissPopup(open) },
        modifier = Modifier
            .width(SessionCatalogWidth)
            .background(SettingsDialogHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Sessions",
                modifier = Modifier.fillMaxWidth().background(SettingsDialogHeaderBackground),
                color = SettingsDialogForeground,
                textStyle = TuiTheme.typography.headline,
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().height(SessionCatalogRows)) {
                when (val current = state) {
                    SessionCatalogState.Unloaded,
                    SessionCatalogState.Loading,
                        -> item { SessionCatalogLoadingIndicator() }

                    is SessionCatalogState.Loaded -> if (current.sessions.isEmpty()) {
                        item { Text("No persisted sessions", color = SettingsDialogForeground) }
                    } else {
                        items(current.sessions, key = { entry -> entry.sessionIndex }) { entry ->
                            TuiButton(
                                label = entry.sessionBrowserLabel(SessionCatalogWidth - 2),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SettingsDialogNavigationBackground),
                                color = SettingsDialogForeground,
                                onClick = {
                                    scope.launch {
                                        application.openSession(entry.sessionIndex)
                                        application.dismissPopup(open)
                                    }
                                },
                                onSecondaryClick = {
                                    scope.launch {
                                        application.openDeleteSessionPopup(entry.sessionIndex)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsDialogActionBackground),
            ) {
                TuiButton(
                    label = "Close",
                    color = SettingsDialogForeground,
                    onClick = { application.dismissPopup(open) },
                )
            }
        }
    }
}

@Composable
internal fun SessionCatalogLoadingIndicator() {
    val frame by rememberRunningIndicatorFrame(active = true)
    Text("$frame Loading sessions…", color = SettingsDialogForeground)
}

@Composable
private fun BoxScope.RenameSessionPopup(
    application: ApplicationViewModel,
    open: ApplicationPopupState.RenameSession,
) {
    val scope = rememberCoroutineScope()
    val draft by open.viewModel.draftName.collectAsState()
    val input = remember(open) {
        TextInputState(TextInputValue(draft, draft.length))
    }
    LaunchedEffect(draft) {
        if (input.value.text != draft) input.reset(TextInputValue(draft, draft.length))
    }
    TuiDialog(
        onDismissRequest = { application.dismissPopup(open) },
        modifier = Modifier.width(RenameDialogWidth).background(SettingsDialogHomeBackground),
    ) {
        SessionRenameEditor(
            draftName = draft,
            input = input,
            width = RenameDialogWidth - 2,
            onDraftNameChanged = open.viewModel::updateDraftName,
            onSubmit = {
                scope.launch {
                    open.viewModel.rename()
                    application.dismissPopup(open)
                }
            },
        )
    }
}

@Composable
internal fun SessionRenameEditor(
    draftName: String,
    input: TextInputState,
    width: Int,
    onDraftNameChanged: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column {
        Text("Rename session", textStyle = TuiTheme.typography.title)
        TextInput(
            state = input,
            layout = TextInputLayout.create(input.value, width, "> ", "  "),
            onValueChanged = { value -> onDraftNameChanged(value.text) },
            autoFocus = true,
            onKeyEvent = { event ->
                if (
                    draftName.isNotBlank() &&
                    event.key == "Enter" &&
                    !event.shift &&
                    !event.ctrl &&
                    !event.alt
                ) {
                    onSubmit()
                    true
                } else {
                    false
                }
            },
        )
    }
}

@Composable
private fun BoxScope.DeleteSessionPopup(
    application: ApplicationViewModel,
    open: ApplicationPopupState.DeleteSession,
) {
    val scope = rememberCoroutineScope()
    val target = open.viewModel.threadName ?: "Session ${open.viewModel.sessionIndex}"
    TuiDialog(
        onDismissRequest = { application.dismissPopup(open) },
        modifier = Modifier.width(DeleteDialogWidth).background(SettingsDialogHomeBackground),
    ) {
        Column {
            Text("Delete $target?", textStyle = TuiTheme.typography.title)
            Text(
                "This removes the persisted session.",
                textStyle = TuiTheme.typography.supporting,
            )
            TuiDialogActionRow(modifier = Modifier.fillMaxWidth()) {
                TuiButton(
                    label = "Cancel",
                    autoFocus = true,
                    onClick = { application.dismissPopup(open) },
                )
                TuiButton(
                    label = "Delete",
                    color = TuiTheme.colorScheme.error,
                    onClick = {
                        scope.launch {
                            open.viewModel.delete()
                            application.dismissPopup(open)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BoxScope.SessionTabContextMenu(
    request: SessionTabMenuRequest?,
    onDismiss: () -> Unit,
    onClose: (SessionViewModel) -> Unit,
    onRename: (SessionViewModel) -> Unit,
    onDelete: (SessionViewModel) -> Unit,
) {
    val current = request ?: return
    TuiContextMenu(
        expanded = true,
        anchor = current.anchor,
        clickPosition = current.clickPosition,
        onDismissRequest = onDismiss,
        backgroundColor = PopupMenuBackground,
    ) {
        TuiPopupMenuItem(key = "rename", onClick = { onRename(current.target) }) {
            Text("Rename")
        }
        TuiPopupMenuItem(key = "close", onClick = { onClose(current.target) }) {
            Text("Close")
        }
        if (current.target is PersistedSessionViewModel) {
            TuiPopupMenuItem(key = "delete", onClick = { onDelete(current.target) }) {
                Text("Delete")
            }
        }
    }
}

@Composable
private fun BoxScope.HistoryEntryContextMenu(
    request: HistoryEntryMenuRequest?,
    selectedSession: PersistedSessionViewModel?,
    selectedAgent: AgentViewModel?,
    onDismiss: () -> Unit,
    onRevert: (HistoryEntryMenuRequest) -> Unit,
    onFork: (HistoryEntryMenuRequest) -> Unit,
) {
    val current = request ?: return
    val execution by current.agent.execution.collectAsState()
    val window by current.agent.history.window.collectAsState()
    val targetMatches =
        current.session === selectedSession &&
            current.agent === selectedAgent &&
            !execution.running &&
            execution.capabilities.canReplaceHistory &&
            execution.capabilities.canForkHistory &&
            current.target.isCurrentIn(window)
    val anchorPlaced = current.anchor.isPlaced
    LaunchedEffect(current, targetMatches, anchorPlaced) {
        if (!targetMatches || !anchorPlaced) onDismiss()
    }
    if (!targetMatches || !anchorPlaced) return

    HistoryEntryContextMenuPopup(
        anchor = current.anchor,
        clickPosition = current.clickPosition,
        onDismiss = onDismiss,
        onRevert = { onRevert(current) },
        onFork = { onFork(current) },
    )
}

@Composable
internal fun BoxScope.HistoryEntryContextMenuPopup(
    anchor: TuiPopupAnchor,
    clickPosition: IntOffset?,
    onDismiss: () -> Unit,
    onRevert: () -> Unit,
    onFork: () -> Unit,
) {
    TuiContextMenu(
        expanded = true,
        anchor = anchor,
        clickPosition = clickPosition,
        onDismissRequest = onDismiss,
        backgroundColor = PopupMenuBackground,
    ) {
        TuiPopupMenuItem(key = "revert-to-here", onClick = onRevert) {
            Text("Revert to here")
        }
        TuiPopupMenuItem(key = "fork-from-here", onClick = onFork) {
            Text("Fork from here")
        }
    }
}

@Composable
private fun BoxScope.AgentHistoryRevertDialog(agent: AgentViewModel?) {
    if (agent == null) return
    val action by agent.historyAction.collectAsState()
    val confirm =
        action as? AgentHistoryActionState.ConfirmRevert
            ?: return
    val execution by agent.execution.collectAsState()
    val window by agent.history.window.collectAsState()
    val targetMatches =
        !execution.running &&
            execution.capabilities.canReplaceHistory &&
            confirm.target.isCurrentIn(window)
    LaunchedEffect(agent, confirm.requestId, targetMatches) {
        if (!targetMatches) agent.dismissHistoryRevert(confirm.requestId)
    }
    if (!targetMatches) return
    DisposableEffect(agent, confirm.requestId) {
        onDispose {
            agent.dismissHistoryRevert(confirm.requestId)
        }
    }
    TuiDialog(
        onDismissRequest = { agent.dismissHistoryRevert(confirm.requestId) },
        modifier = Modifier.width(RevertDialogWidth).background(SettingsDialogHomeBackground),
    ) {
        Column {
            Text("Revert history to here?", textStyle = TuiTheme.typography.title)
            Text(
                "Keep the selected history entry and remove everything after it.",
                textStyle = TuiTheme.typography.supporting,
            )
            Text(
                "This cannot be undone.",
                textStyle = TuiTheme.typography.supporting,
            )
            TuiDialogActionRow(modifier = Modifier.fillMaxWidth()) {
                TuiButton(
                    label = "Cancel",
                    autoFocus = true,
                    onClick = { agent.dismissHistoryRevert(confirm.requestId) },
                )
                TuiButton(
                    label = "Revert",
                    color = TuiTheme.colorScheme.error,
                    onClick = {
                        runCatching {
                            agent.confirmHistoryRevert(confirm.requestId)
                        }
                    },
                )
            }
        }
    }
}

internal fun AgentHistoryTarget.isCurrentIn(
    window: AgentHistoryWindowSnapshot,
): Boolean =
    generation == window.generation &&
        window.entries.any { entry ->
            entry.key.primaryStorageIndex == storageIndex
        }

private data class SessionTabMenuRequest(
    val target: SessionViewModel,
    val name: String,
    val anchor: TuiPopupAnchor,
    val clickPosition: IntOffset?,
)

private data class HistoryEntryMenuRequest(
    val session: PersistedSessionViewModel,
    val agent: AgentViewModel,
    val target: AgentHistoryTarget,
    val anchor: TuiPopupAnchor,
    val clickPosition: IntOffset?,
)

private const val SessionTabBarRows: Int = 1
private const val SessionCatalogWidth: Int = 64
private const val SessionCatalogRows: Int = 16
private const val RenameDialogWidth: Int = 48
private const val DeleteDialogWidth: Int = 52
private const val RevertDialogWidth: Int = 56
