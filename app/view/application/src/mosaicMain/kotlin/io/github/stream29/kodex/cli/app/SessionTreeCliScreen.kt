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
import com.jakewharton.mosaic.animation.animateIntAsState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Arrangement
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.AgentHistoryTarget
import io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState
import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiCheckbox
import io.github.stream29.kodex.cli.components.TuiContextMenu
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.items
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiColorSchemeFor
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
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
    var sidebarExpandButtonHovered by remember { mutableStateOf(false) }
    var sidebarSurfaceHovered by remember { mutableStateOf(false) }
    var shellSessionMenu by remember { mutableStateOf<ShellSessionMenuRequest?>(null) }
    var tabMenu by remember { mutableStateOf<SessionTabMenuRequest?>(null) }
    var historyMenu by remember { mutableStateOf<HistoryEntryMenuRequest?>(null) }
    val sidebarExpanded = sidebarPinnedExpanded ||
        sidebarExpandButtonHovered ||
        sidebarSurfaceHovered ||
        shellSessionMenu != null
    val animatedSidebarColumns by animateIntAsState(
        targetValue = if (sidebarExpanded) SessionSidebarExpandedColumns else 0,
        label = "session sidebar width",
    )
    val sidebarColumns = animatedSidebarColumns.coerceIn(0, SessionSidebarExpandedColumns)
    val expandButtonBridgesHoverAnimation = sidebarExpandButtonHovered &&
        !sidebarPinnedExpanded &&
        sidebarColumns < SessionSidebarExpandedColumns
    val showSidebarExpandButton =
        (!sidebarExpanded && sidebarColumns == 0) || expandButtonBridgesHoverAnimation
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

    TuiTheme(colorScheme = tuiColorSchemeFor(terminal.theme)) {
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
                        if (sidebarColumns > 0) {
                            SessionAgentSidebar(
                                topology = topology,
                                selectedAgent = selectedAgent,
                                columns = sidebarColumns,
                                rows = contentRows,
                                runningIndicatorFrame = runningFrame,
                                onHoverChanged = { sidebarSurfaceHovered = it },
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
                    if (showSidebarExpandButton) {
                        SessionAgentSidebarExpandButton(
                            onHoverChanged = { hovered -> sidebarExpandButtonHovered = hovered },
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
                onCloseAndArchive = { target ->
                    tabMenu = null
                    scope.launch { viewModel.closeAndArchiveSession(target) }
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

                is ApplicationPopupState.Settings -> TuiTheme(
                    colorScheme = tuiColorSchemeFor(terminal.theme),
                ) {
                    SettingsPopup(
                        viewModel = open.viewModel,
                        onDismissRequest = { viewModel.dismissPopup(open) },
                        onOpenLogin = {
                            scope.launch { viewModel.openLoginPopup() }
                        },
                    )
                }

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
    var contextMenu by remember(open) { mutableStateOf<SessionCatalogMenuRequest?>(null) }
    var deleteTarget by remember(open) { mutableStateOf<SessionCatalogEntry?>(null) }
    LaunchedEffect(open) { open.viewModel.refresh() }
    TuiDialog(
        onDismissRequest = { application.dismissPopup(open) },
        modifier = Modifier
            .width(SessionCatalogWidth)
            .background(SettingsDialogHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SessionCatalogHeader(
                showArchived = state.showArchived,
                onShowArchivedChange = { showArchived ->
                    contextMenu = null
                    scope.launch { open.viewModel.setShowArchived(showArchived) }
                },
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().height(SessionCatalogRows)) {
                when (val current = state) {
                    SessionCatalogState.Unloaded,
                    is SessionCatalogState.Loading,
                        -> item { SessionCatalogLoadingIndicator() }

                    is SessionCatalogState.Loaded -> if (current.sessions.isEmpty()) {
                        item { Text("No persisted sessions", color = SettingsDialogForeground) }
                    } else {
                        items(current.sessions, key = { entry -> entry.sessionIndex }) { entry ->
                            SessionCatalogRow(
                                entry = entry,
                                maximumLabelColumns = SessionCatalogWidth - 2,
                                onClick = {
                                    contextMenu = null
                                    scope.launch {
                                        application.openSession(entry.sessionIndex)
                                        application.dismissPopup(open)
                                    }
                                },
                                onOpenContextMenu = { anchor, clickPosition ->
                                    contextMenu = SessionCatalogMenuRequest(
                                        entry = entry,
                                        anchor = anchor,
                                        clickPosition = clickPosition,
                                    )
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
    contextMenu?.let { request ->
        SessionCatalogContextMenuPopup(
            entry = request.entry,
            anchor = request.anchor,
            clickPosition = request.clickPosition,
            onDismissRequest = { contextMenu = null },
            onFork = {
                contextMenu = null
                scope.launch {
                    open.viewModel.fork(request.entry.sessionIndex)
                }
            },
            onArchive = {
                contextMenu = null
                scope.launch {
                    open.viewModel.archive(request.entry.sessionIndex)
                }
            },
            onUnarchive = {
                contextMenu = null
                scope.launch {
                    open.viewModel.unarchive(request.entry.sessionIndex)
                }
            },
            onDelete = {
                contextMenu = null
                deleteTarget = request.entry
            },
        )
    }
    deleteTarget?.let { target ->
        SessionCatalogDeleteDialog(
            target = target,
            onDismissRequest = { deleteTarget = null },
            onDelete = {
                scope.launch {
                    if (open.viewModel.delete(target.sessionIndex)) {
                        deleteTarget = null
                    }
                }
            },
        )
    }
}

@Composable
internal fun SessionCatalogHeader(
    showArchived: Boolean,
    onShowArchivedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SettingsDialogHeaderBackground),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "Sessions",
            color = SettingsDialogForeground,
            textStyle = TuiTheme.typography.headline,
        )
        TuiCheckbox(
            label = "Show archived",
            checked = showArchived,
            onCheckedChange = onShowArchivedChange,
            color = SettingsDialogForeground,
        )
    }
}

@Composable
internal fun SessionCatalogRow(
    entry: SessionCatalogEntry,
    maximumLabelColumns: Int,
    onClick: () -> Unit,
    onOpenContextMenu: (TuiPopupAnchor, IntOffset?) -> Unit,
) {
    val anchor = rememberTuiPopupAnchor()
    TuiButton(
        label = entry.sessionBrowserLabel(maximumLabelColumns),
        modifier = Modifier
            .fillMaxWidth()
            .background(SettingsDialogNavigationBackground)
            .tuiPopupAnchor(anchor),
        color = SettingsDialogForeground,
        onClick = onClick,
        onSecondaryClick = { clickPosition ->
            onOpenContextMenu(anchor, clickPosition)
        },
    )
}

@Composable
internal fun BoxScope.SessionCatalogContextMenuPopup(
    entry: SessionCatalogEntry,
    anchor: TuiPopupAnchor,
    clickPosition: IntOffset?,
    onDismissRequest: () -> Unit,
    onFork: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
) {
    TuiContextMenu(
        expanded = true,
        anchor = anchor,
        clickPosition = clickPosition,
        onDismissRequest = onDismissRequest,
        backgroundColor = PopupMenuBackground,
    ) {
        TuiPopupMenuItem(
            key = if (entry.archived) "unarchive" else "archive",
            onClick = if (entry.archived) onUnarchive else onArchive,
        ) {
            Text(if (entry.archived) "Unarchive" else "Archive")
        }
        TuiPopupMenuItem(key = "delete", onClick = onDelete) {
            Text("Delete")
        }
        TuiPopupMenuItem(key = "fork", onClick = onFork) {
            Text("Fork")
        }
    }
}

@Composable
private fun BoxScope.SessionCatalogDeleteDialog(
    target: SessionCatalogEntry,
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
) {
    val title = target.threadName ?: "Session ${target.sessionIndex}"
    TuiDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(DeleteDialogWidth).background(SettingsDialogHomeBackground),
    ) {
        Column {
            Text("Delete $title?", textStyle = TuiTheme.typography.title)
            Text(
                "This removes the persisted session.",
                textStyle = TuiTheme.typography.supporting,
            )
            TuiDialogActionRow(modifier = Modifier.fillMaxWidth()) {
                TuiButton(
                    label = "Cancel",
                    autoFocus = true,
                    onClick = onDismissRequest,
                )
                TuiButton(
                    label = "Delete",
                    color = TuiTheme.colorScheme.error,
                    onClick = onDelete,
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
    onCloseAndArchive: (PersistedSessionViewModel) -> Unit,
    onRename: (SessionViewModel) -> Unit,
    onDelete: (SessionViewModel) -> Unit,
) {
    val current = request ?: return
    SessionTabContextMenuPopup(
        target = current.target,
        anchor = current.anchor,
        clickPosition = current.clickPosition,
        onDismiss = onDismiss,
        onClose = { onClose(current.target) },
        onCloseAndArchive = onCloseAndArchive,
        onRename = { onRename(current.target) },
        onDelete = { onDelete(current.target) },
    )
}

@Composable
internal fun BoxScope.SessionTabContextMenuPopup(
    target: SessionViewModel,
    anchor: TuiPopupAnchor,
    clickPosition: IntOffset?,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
    onCloseAndArchive: (PersistedSessionViewModel) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    TuiContextMenu(
        expanded = true,
        anchor = anchor,
        clickPosition = clickPosition,
        onDismissRequest = onDismiss,
        backgroundColor = PopupMenuBackground,
    ) {
        TuiPopupMenuItem(key = "rename", onClick = onRename) {
            Text("Rename")
        }
        TuiPopupMenuItem(key = "close", onClick = onClose) {
            Text("Close")
        }
        if (target is PersistedSessionViewModel) {
            TuiPopupMenuItem(
                key = "close-and-archive",
                onClick = { onCloseAndArchive(target) },
            ) {
                Text("Close and archive")
            }
            TuiPopupMenuItem(key = "delete", onClick = onDelete) {
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
    val historyItems by current.agent.history.historyItems.collectAsState()
    val targetMatches =
        current.session === selectedSession &&
            current.agent === selectedAgent &&
            !execution.running &&
            execution.capabilities.canReplaceHistory &&
            execution.capabilities.canForkHistory &&
            historyItems.size > 0 &&
            current.target.generation == historyItems.generation &&
            current.agent.history.contains(
                current.target.generation,
                current.target.storageIndex,
            )
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
    val historyItems by agent.history.historyItems.collectAsState()
    val targetMatches =
        !execution.running &&
            execution.capabilities.canReplaceHistory &&
            historyItems.size > 0 &&
            confirm.target.generation == historyItems.generation &&
            agent.history.contains(
                confirm.target.generation,
                confirm.target.storageIndex,
            )
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

private data class SessionTabMenuRequest(
    val target: SessionViewModel,
    val name: String,
    val anchor: TuiPopupAnchor,
    val clickPosition: IntOffset?,
)

private data class SessionCatalogMenuRequest(
    val entry: SessionCatalogEntry,
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
