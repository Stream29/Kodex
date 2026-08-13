package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.animation.animateIntAsState
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
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationNavigationState
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.application.contract.DeleteSessionPopupViewModel
import io.github.stream29.kodex.app.application.contract.RenameSessionPopupViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiContextMenu
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.cli.components.items
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.OpenAiLoginPopup
import io.github.stream29.kodex.cli.settings.SettingsPopup
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
    val sidebarColumns by animateIntAsState(
        targetValue = if (sidebarExpanded) {
            SessionSidebarExpandedColumns
        } else {
            SessionSidebarCollapsedColumns
        },
        label = "agent sidebar width",
    )
    val contentColumns = (columns - sidebarColumns).coerceAtLeast(1)
    val contentRows = (rows - SessionTabBarRows).coerceAtLeast(0)
    val selected = navigation.selected
    val selectedPersisted = selected as? PersistedSessionViewModel
    val selectedAgent = collectSelectedAgent(selectedPersisted)
    val topology = collectTopology(selectedPersisted)

    TuiPopupHost {
        Column(modifier = Modifier.width(columns).height(rows)) {
            SessionTabBar(
                tabs = tabStates,
                runningIndicatorFrame = runningFrame,
                columns = columns,
                onSelectTab = { target ->
                    val index = navigation.tabs.indexOfFirst { child -> child === target }
                    if (index >= 0) scope.launch { viewModel.selectTab(index) }
                },
                onOpenTabMenu = { target, name, anchor, position ->
                    tabMenu = SessionTabMenuRequest(target, name, anchor, position)
                },
                onCreateNewSession = {
                    scope.launch { viewModel.createNewSessionTab() }
                },
                onOpenSessions = {
                    scope.launch { viewModel.openSessionCatalogPopup() }
                },
            )
            Row(modifier = Modifier.width(columns).height(contentRows)) {
                SessionAgentSidebar(
                    topology = topology,
                    selectedAgent = selectedAgent,
                    expanded = sidebarExpanded,
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
                        selectedPersisted?.let { session ->
                            scope.launch { session.selectAgent(address) }
                        }
                    },
                    onOpenShellSessionMenu = { shellSessionMenu = it },
                )
                Box(modifier = Modifier.width(contentColumns).height(contentRows)) {
                    when (selected) {
                        is NewSessionViewModel -> NewSessionScreen(
                            viewModel = selected,
                            columns = contentColumns,
                            rows = contentRows,
                            newLineKey = currentNewLineKey,
                            onSubmit = {
                                val index = navigation.tabs.indexOfFirst { it === selected }
                                if (index >= 0) {
                                    scope.launch { viewModel.materializeNewSession(index) }
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
                                    onOpenHistoryEntryContextMenu = { target, anchor, position ->
                                        historyMenu = HistoryEntryMenuRequest(
                                            session = selected,
                                            agent = agent,
                                            target = target,
                                            anchor = anchor,
                                            clickPosition = position,
                                        )
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
            onDismiss = { historyMenu = null },
            onRevert = { request ->
                historyMenu = null
                request.agent.requestHistoryRevert(request.target)
            },
            onFork = { request ->
                historyMenu = null
                scope.launch {
                    val index = request.session.fork(request.agent, request.target)
                    viewModel.openSession(index)
                }
            },
        )
        AgentHistoryRevertDialog(selectedAgent)

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
        }

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
    val sessions by open.viewModel.sessions.collectAsState()
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
                textStyle = TextStyle.Bold,
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().height(SessionCatalogRows)) {
                if (sessions.isEmpty()) {
                    item { Text("No persisted sessions", color = SettingsDialogForeground) }
                } else {
                    items(sessions, key = { entry -> entry.sessionIndex }) { entry ->
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
            TuiButton(
                label = "Close",
                color = SettingsDialogForeground,
                onClick = { application.dismissPopup(open) },
            )
        }
    }
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
        Column {
            Text("Rename session", textStyle = TextStyle.Bold)
            TextInput(
                state = input,
                layout = TextInputLayout.create(input.value, RenameDialogWidth - 2, "> ", "  "),
                onValueChanged = { value -> open.viewModel.updateDraftName(value.text) },
            )
            Row {
                TuiButton(
                    label = "Rename",
                    enabled = draft.isNotBlank(),
                    onClick = {
                        scope.launch {
                            open.viewModel.rename()
                            application.dismissPopup(open)
                        }
                    },
                )
                Text(" ")
                TuiButton(
                    label = "Cancel",
                    onClick = { application.dismissPopup(open) },
                )
            }
        }
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
            Text("Delete $target?", textStyle = TextStyle.Bold)
            Text("This removes the persisted session.", textStyle = TextStyle.Dim)
            Row {
                TuiButton(
                    label = "Delete",
                    onClick = {
                        scope.launch {
                            open.viewModel.delete()
                            application.dismissPopup(open)
                        }
                    },
                )
                Text(" ")
                TuiButton(
                    label = "Cancel",
                    onClick = { application.dismissPopup(open) },
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
    onDismiss: () -> Unit,
    onRevert: (HistoryEntryMenuRequest) -> Unit,
    onFork: (HistoryEntryMenuRequest) -> Unit,
) {
    val current = request ?: return
    TuiContextMenu(
        expanded = true,
        anchor = current.anchor,
        clickPosition = current.clickPosition,
        onDismissRequest = onDismiss,
        backgroundColor = PopupMenuBackground,
    ) {
        TuiPopupMenuItem(key = "revert", onClick = { onRevert(current) }) {
            Text("Revert through here")
        }
        TuiPopupMenuItem(key = "fork", onClick = { onFork(current) }) {
            Text("Fork through here")
        }
    }
}

@Composable
private fun BoxScope.AgentHistoryRevertDialog(agent: AgentViewModel?) {
    if (agent == null) return
    val action by agent.historyAction.collectAsState()
    val confirm =
        action as? io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState.ConfirmRevert
            ?: return
    val scope = rememberCoroutineScope()
    TuiDialog(
        onDismissRequest = { agent.dismissHistoryRevert(confirm.requestId) },
        modifier = Modifier.width(RevertDialogWidth).background(SettingsDialogHomeBackground),
    ) {
        Column {
            Text("Revert history?", textStyle = TextStyle.Bold)
            Text(
                "Committed items after ${confirm.target.storageIndex} will be removed.",
                textStyle = TextStyle.Dim,
            )
            Row {
                TuiButton(
                    label = "Revert",
                    onClick = {
                        scope.launch { agent.confirmHistoryRevert(confirm.requestId) }
                    },
                )
                Text(" ")
                TuiButton(
                    label = "Cancel",
                    onClick = { agent.dismissHistoryRevert(confirm.requestId) },
                )
            }
        }
    }
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
