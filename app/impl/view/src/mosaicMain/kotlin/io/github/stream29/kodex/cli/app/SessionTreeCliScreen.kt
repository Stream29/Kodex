package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationDropdowns
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationMenus
import io.github.stream29.kodex.cli.sessioncatalog.SessionCatalogPopup
import io.github.stream29.kodex.cli.historyindex.HistoryIndexInteractionRequest
import io.github.stream29.kodex.cli.historyindex.HistoryIndexMenuRequest
import io.github.stream29.kodex.cli.historyindex.HistoryIndexSide
import io.github.stream29.kodex.cli.historyindex.HistoryIndexHoverPopup
import io.github.stream29.kodex.cli.historyindex.HistoryIndexContextMenu

import io.github.stream29.kodex.cli.agent.SuggestSubagentTaskDropdowns
import io.github.stream29.kodex.cli.agent.SuggestSubagentTaskConfigurationMenus

import io.github.stream29.kodex.cli.agent.canEditHistory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.animation.animateIntAsState
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Arrangement
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState
import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.application.contract.SidebarSettingsViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.SettingsDialogHomeBackground
import io.github.stream29.kodex.cli.components.rememberRunningIndicatorFrame
import io.github.stream29.kodex.cli.components.TuiCheckbox
import io.github.stream29.kodex.cli.components.TuiContextMenu
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.items
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiColorSchemeFor
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabBarCallbacks
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabBarState
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabIdentity
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabPresentation
import io.github.stream29.kodex.cli.sessiontabbar.SessionTabBar as ComponentSessionTabBar
import io.github.stream29.kodex.cli.workingdirectory.WorkingDirectoryPopup
import io.github.stream29.kodex.app.pathpicker.createDirectoryPickerViewModel
import io.github.stream29.kodex.app.workingdirectory.createWorkingDirectoryViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryDependencies
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.OpenAiLoginPopup
import io.github.stream29.kodex.cli.settings.SettingsPopup
import io.github.stream29.kodex.cli.settings.McpSettingsEffects
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.app.sessiondelete.createSessionDeleteViewModel
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteDependencies
import io.github.stream29.kodex.cli.sessiondelete.SessionDeletePopup
import io.github.stream29.kodex.cli.sessionrename.SessionRenamePopup
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.utils.externalurl.OpenExternalUrlResult
import io.github.stream29.kodex.utils.externalurl.openExternalUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** Application shell; all mutable child state is collected by its exact renderer. */
@Composable
public fun SessionTreeCliScreen(
    viewModel: ApplicationViewModel,
    newLineKey: StateFlow<NewLineKey>,
    sidebarSettings: SidebarSettingsViewModel,
    openMcpUrl: suspend (String) -> Boolean = { url ->
        openExternalUrl(url) !is OpenExternalUrlResult.Failed
    },
) {
    val terminal = LocalTerminalState.current
    val navigation by viewModel.navigation.collectAsState()
    val popup by viewModel.popup.collectAsState()
    // Login changes the visible popup, not the retained Settings operation owner.
    val settingsPopupOwner = when (val open = popup) {
        is ApplicationPopupState.Settings -> open
        is ApplicationPopupState.Login -> open.returnTo
        else -> null
    }
    if (settingsPopupOwner != null) key(settingsPopupOwner) {
        McpSettingsEffects(settingsPopupOwner.viewModel.global.mcpSettings, openMcpUrl)
    }
    val currentNewLineKey by newLineKey.collectAsState()
    val sidebarConfiguration by sidebarSettings.state.collectAsState()
    val tabStates = collectSessionTabRenderStates(navigation.tabs, navigation.selectedIndex)
    val tabIdentityCounter = remember { mutableStateOf(0) }
    val tabIdentities = remember { mutableMapOf<SessionViewModel, SessionTabIdentity>() }
    pruneSessionTabIdentities(tabIdentities, navigation.tabs)
    val componentTabEntries = tabStates.map { tab ->
        val identity = tabIdentities.getOrPut(tab.target) {
            tabIdentityCounter.value += 1
            SessionTabIdentity("tab-${tabIdentityCounter.value}")
        }
        identity to (
            tab.target to SessionTabPresentation(
                identity = identity,
                label = tab.sessionName,
                running = tab.running,
            )
        )
    }
    val componentTabState = SessionTabBarState(
        tabs = componentTabEntries.map { it.second.second },
        selected = componentTabEntries
            .getOrNull(navigation.selectedIndex)
            ?.first,
    )
    fun targetFor(identity: SessionTabIdentity): SessionViewModel? =
        componentTabEntries.firstOrNull { it.first == identity }?.second?.first
    val sessionSummary = summarizeOpenSessions(tabStates)
    val runningFrame = rememberRunningIndicatorFrame(
        active = sessionSummary.runningSessionCount > 0,
    )
    TerminalTitleEffect(sessionSummary.sessionCount, sessionSummary.runningSessionCount)

    val columns = (terminal.size.columns - 1).coerceAtLeast(1)
    val rows = (terminal.size.rows - 1).coerceAtLeast(1)
    LaunchedEffect(sidebarSettings, columns) { sidebarSettings.initializeViewport(columns) }
    val scope = rememberCoroutineScope()
    var leftSidebarPinnedExpanded by remember { mutableStateOf(false) }
    var leftSidebarExpandButtonHovered by remember { mutableStateOf(false) }
    var leftSidebarSurfaceHovered by remember { mutableStateOf(false) }
    var leftSidebarResizing by remember { mutableStateOf(false) }
    var leftSidebarResizeColumns by remember { mutableStateOf<Int?>(null) }
    var rightSidebarPinnedExpanded by remember { mutableStateOf(false) }
    var rightSidebarExpandButtonHovered by remember { mutableStateOf(false) }
    var rightSidebarSurfaceHovered by remember { mutableStateOf(false) }
    var rightSidebarResizing by remember { mutableStateOf(false) }
    var rightSidebarResizeColumns by remember { mutableStateOf<Int?>(null) }
    val leftSidebarDropdown = rememberTuiDropdownState()
    val rightSidebarDropdown = rememberTuiDropdownState()
    var shellSessionMenu by remember { mutableStateOf<SidebarShellSessionMenuRequest?>(null) }
    var shellSessionHover by remember { mutableStateOf<ShellSessionInteractionRequest?>(null) }
    var shellSessionPopupHovered by remember { mutableStateOf(false) }
    var shellSessionHoverCloseJob by remember { mutableStateOf<Job?>(null) }
    var historyIndexHover by remember { mutableStateOf<HistoryIndexInteractionRequest?>(null) }
    var historyIndexPopupHovered by remember { mutableStateOf(false) }
    var historyIndexHoverCloseJob by remember { mutableStateOf<Job?>(null) }
    var historyIndexMenu by remember { mutableStateOf<HistoryIndexMenuRequest?>(null) }
    var tabMenu by remember { mutableStateOf<SessionTabMenuRequest?>(null) }
    var historyMenu by remember { mutableStateOf<HistoryEntryMenuRequest?>(null) }
    val leftSidebarExpanded = leftSidebarPinnedExpanded ||
        leftSidebarExpandButtonHovered ||
        leftSidebarSurfaceHovered ||
        leftSidebarResizing ||
        leftSidebarDropdown.expanded ||
        shellSessionMenu?.side == SessionSidebarSide.Left ||
        shellSessionHover?.side == SessionSidebarSide.Left ||
        historyIndexHover?.side == HistoryIndexSide.Left ||
        historyIndexMenu?.target?.side == HistoryIndexSide.Left
    val rightSidebarExpanded = rightSidebarPinnedExpanded ||
        rightSidebarExpandButtonHovered ||
        rightSidebarSurfaceHovered ||
        rightSidebarResizing ||
        rightSidebarDropdown.expanded ||
        shellSessionMenu?.side == SessionSidebarSide.Right ||
        shellSessionHover?.side == SessionSidebarSide.Right ||
        historyIndexHover?.side == HistoryIndexSide.Right ||
        historyIndexMenu?.target?.side == HistoryIndexSide.Right
    val leftSidebarPreferredColumns =
        leftSidebarResizeColumns ?: sidebarConfiguration.leftWidth
    val rightSidebarPreferredColumns =
        rightSidebarResizeColumns ?: sidebarConfiguration.rightWidth
    val targetSidebarColumns = resolveSessionSidebarColumns(
        columns = columns,
        leftColumns = if (leftSidebarExpanded) leftSidebarPreferredColumns else 0,
        rightColumns = if (rightSidebarExpanded) rightSidebarPreferredColumns else 0,
    )
    val animatedLeftSidebarColumns by animateIntAsState(
        targetValue = targetSidebarColumns.left,
        label = "left session sidebar width",
    )
    val animatedRightSidebarColumns by animateIntAsState(
        targetValue = targetSidebarColumns.right,
        label = "right session sidebar width",
    )
    val displayedSidebarColumns = resolveSessionSidebarColumns(
        columns = columns,
        leftColumns = leftSidebarResizeColumns ?: animatedLeftSidebarColumns,
        rightColumns = rightSidebarResizeColumns ?: animatedRightSidebarColumns,
    )
    val leftSidebarColumns = displayedSidebarColumns.left
    val rightSidebarColumns = displayedSidebarColumns.right
    val leftExpandButtonBridgesHoverAnimation = leftSidebarExpandButtonHovered &&
        !leftSidebarPinnedExpanded &&
        leftSidebarColumns < targetSidebarColumns.left
    val rightExpandButtonBridgesHoverAnimation = rightSidebarExpandButtonHovered &&
        !rightSidebarPinnedExpanded &&
        rightSidebarColumns < targetSidebarColumns.right
    val showLeftSidebarExpandButton =
        (!leftSidebarExpanded && leftSidebarColumns == 0) ||
            leftExpandButtonBridgesHoverAnimation
    val showRightSidebarExpandButton =
        (!rightSidebarExpanded && rightSidebarColumns == 0) ||
            rightExpandButtonBridgesHoverAnimation
    val contentColumns = displayedSidebarColumns.content
    val contentRows = (rows - SessionTabBarRows).coerceAtLeast(0)
    val selected = navigation.selected
    val selectedPersisted = selected as? PersistedSessionViewModel
    val selectedAgent = selectedPersisted?.rootAgent?.collectAsState()?.value
    val selectedLifecycle = selectedPersisted?.lifecycle?.collectAsState()?.value
    val currentSelectedAgent by rememberUpdatedState(selectedAgent)
    val composerFocusRequester = remember(selectedAgent) { FocusRequester() }
    val currentComposerFocusRequester by rememberUpdatedState(composerFocusRequester)
    val settingsOwner: AgentSettingsViewModel? =
        selectedAgent ?: (selected as? NewSessionViewModel)
    val runtimeConfiguration = when (settingsOwner) {
        is AgentViewModel -> settingsOwner.runtimeConfiguration
        is NewSessionViewModel -> settingsOwner.runtimeConfiguration
        else -> null
    }
    val runtimeDropdowns = RuntimeConfigurationDropdowns.remember(runtimeConfiguration)
    val pendingSuggestion = selectedAgent?.let { agent ->
        key(agent) { agent.suggestSubagentTask.state.collectAsState().value }
    } as? io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState.Pending
    val suggestionDropdowns = SuggestSubagentTaskDropdowns.remember(
        selectedAgent?.suggestSubagentTask, pendingSuggestion?.callId,
    )

    fun closeShellSessionHoverAfterGrace(
        request: ShellSessionInteractionRequest? = shellSessionHover,
    ) {
        shellSessionHoverCloseJob?.cancel()
        shellSessionHoverCloseJob = scope.launch {
            delay(SidebarHoverCloseGrace)
            if (
                !shellSessionPopupHovered &&
                (request == null || shellSessionHover == request)
            ) {
                shellSessionHover = null
            }
        }
    }

    fun updateShellSessionRowHover(
        request: ShellSessionInteractionRequest,
        hovered: Boolean,
    ) {
        if (hovered) {
            if (shellSessionMenu != null || historyIndexMenu != null) return
            historyIndexHoverCloseJob?.cancel()
            historyIndexHover = null
            historyIndexPopupHovered = false
            shellSessionHoverCloseJob?.cancel()
            shellSessionPopupHovered = false
            shellSessionHover = request
        } else if (shellSessionHover == request) {
            closeShellSessionHoverAfterGrace(request)
        }
    }

    fun closeHistoryIndexHoverAfterGrace(
        request: HistoryIndexInteractionRequest? = historyIndexHover,
    ) {
        historyIndexHoverCloseJob?.cancel()
        historyIndexHoverCloseJob = scope.launch {
            delay(SidebarHoverCloseGrace)
            if (
                !historyIndexPopupHovered &&
                (request == null || historyIndexHover == request)
            ) {
                historyIndexHover = null
            }
        }
    }

    fun updateHistoryIndexRowHover(
        request: HistoryIndexInteractionRequest,
        hovered: Boolean,
    ) {
        if (hovered) {
            if (historyIndexMenu != null || shellSessionMenu != null) return
            shellSessionHoverCloseJob?.cancel()
            shellSessionHover = null
            shellSessionPopupHovered = false
            historyIndexHoverCloseJob?.cancel()
            historyIndexPopupHovered = false
            historyIndexHover = request
        } else if (historyIndexHover == request) {
            closeHistoryIndexHoverAfterGrace(request)
        }
    }

    LaunchedEffect(
        selectedAgent,
        sidebarConfiguration.left,
        sidebarConfiguration.right,
        popup,
    ) {
        shellSessionHoverCloseJob?.cancel()
        shellSessionHover = null
        shellSessionPopupHovered = false
        historyIndexHoverCloseJob?.cancel()
        historyIndexHover = null
        historyIndexPopupHovered = false
        historyIndexMenu = null
    }
    DisposableEffect(Unit) {
        onDispose {
            shellSessionHoverCloseJob?.cancel()
            historyIndexHoverCloseJob?.cancel()
        }
    }

    LaunchedEffect(
        leftSidebarResizing,
        leftSidebarResizeColumns,
        sidebarConfiguration.leftWidth,
        leftSidebarExpanded,
        animatedLeftSidebarColumns,
        targetSidebarColumns.left,
    ) {
        val resized = leftSidebarResizeColumns ?: return@LaunchedEffect
        if (
            !leftSidebarResizing &&
            (
                !leftSidebarExpanded ||
                    (
                        sidebarConfiguration.leftWidth == resized &&
                            animatedLeftSidebarColumns == targetSidebarColumns.left
                        )
                )
        ) {
            leftSidebarResizeColumns = null
        }
    }
    LaunchedEffect(
        rightSidebarResizing,
        rightSidebarResizeColumns,
        sidebarConfiguration.rightWidth,
        rightSidebarExpanded,
        animatedRightSidebarColumns,
        targetSidebarColumns.right,
    ) {
        val resized = rightSidebarResizeColumns ?: return@LaunchedEffect
        if (
            !rightSidebarResizing &&
            (
                !rightSidebarExpanded ||
                    (
                        sidebarConfiguration.rightWidth == resized &&
                            animatedRightSidebarColumns == targetSidebarColumns.right
                        )
                )
        ) {
            rightSidebarResizeColumns = null
        }
    }

    TuiTheme(colorScheme = tuiColorSchemeFor(terminal.theme)) {
        TuiPopupHost {
            Column(modifier = Modifier.width(columns).height(rows)) {
                ComponentSessionTabBar(
                    state = componentTabState,
                    runningIndicatorFrame = runningFrame,
                    columns = columns,
                    callbacks = object : SessionTabBarCallbacks {
                        override fun select(identity: SessionTabIdentity) {
                            val target = targetFor(identity) ?: return
                            scope.launch { viewModel.selectTab(target) }
                        }

                        override fun close(identity: SessionTabIdentity) {
                            val target = targetFor(identity) ?: return
                            if (navigation.tabs.any { child -> child === target }) {
                                scope.launch { viewModel.closeTab(target) }
                            }
                        }

                        override fun openContextMenu(identity: SessionTabIdentity) {
                            // The geometry-bearing callback below opens the existing
                            // Application-owned popup after this admission intent.
                        }

                        override fun createNewSession() {
                            historyMenu = null
                            scope.launch { viewModel.createNewSessionTab() }
                        }

                        override fun openSessions() {
                            historyMenu = null
                            scope.launch { viewModel.openSessionCatalogPopup() }
                        }
                    },
                    onOpenTabMenu = { request ->
                        val target = targetFor(request.identity)
                        if (target != null && navigation.tabs.any { child -> child === target }) {
                            shellSessionMenu = null
                            shellSessionHoverCloseJob?.cancel()
                            shellSessionHover = null
                            historyMenu = null
                            historyIndexHover = null
                            historyIndexMenu = null
                            tabMenu = SessionTabMenuRequest(
                                target,
                                request.label,
                                request.anchor,
                                request.clickPosition,
                            )
                        }
                    },
                )
                Box(modifier = Modifier.width(columns).height(contentRows)) {
                    Row(modifier = Modifier.width(columns).height(contentRows)) {
                        if (leftSidebarColumns > 0) {
                            SessionSidebar(
                                side = SessionSidebarSide.Left,
                                content = sidebarConfiguration.left,
                                selectedAgent = selectedAgent,
                                dropdownState = leftSidebarDropdown,
                                columns = leftSidebarColumns,
                                rows = contentRows,
                                onHoverChanged = { leftSidebarSurfaceHovered = it },
                                onToggleExpanded = {
                                    leftSidebarPinnedExpanded = !leftSidebarPinnedExpanded
                                    if (!leftSidebarPinnedExpanded) {
                                        leftSidebarDropdown.dismiss()
                                        if (shellSessionMenu?.side == SessionSidebarSide.Left) {
                                            shellSessionMenu = null
                                        }
                                        if (shellSessionHover?.side == SessionSidebarSide.Left) {
                                            shellSessionHoverCloseJob?.cancel()
                                            shellSessionHover = null
                                        }
                                    }
                                },
                                onShellSessionHoverChanged = ::updateShellSessionRowHover,
                                onOpenShellSessionMenu = { request ->
                                    shellSessionHoverCloseJob?.cancel()
                                    shellSessionHover = null
                                    shellSessionPopupHovered = false
                                    tabMenu = null
                                    historyMenu = null
                                    historyIndexHover = null
                                    historyIndexMenu = null
                                    shellSessionMenu = SidebarShellSessionMenuRequest(
                                        side = SessionSidebarSide.Left,
                                        request = request,
                                    )
                                },
                                onHistoryIndexHoverChanged = ::updateHistoryIndexRowHover,
                                onOpenHistoryIndexMenu = { request ->
                                    shellSessionHoverCloseJob?.cancel()
                                    shellSessionHover = null
                                    shellSessionPopupHovered = false
                                    historyIndexHoverCloseJob?.cancel()
                                    historyIndexHover = null
                                    historyIndexPopupHovered = false
                                    tabMenu = null
                                    historyMenu = null
                                    shellSessionMenu = null
                                    historyIndexMenu = request
                                },
                                resizable = clampSessionSidebarResize(
                                    columns = columns,
                                    oppositeColumns = rightSidebarColumns,
                                    requestedColumns = leftSidebarColumns,
                                ) != null,
                                onResizeStart = { currentColumns ->
                                    val accepted = clampSessionSidebarResize(
                                        columns = columns,
                                        oppositeColumns = rightSidebarColumns,
                                        requestedColumns = currentColumns,
                                    )
                                    if (accepted != null) {
                                        leftSidebarResizeColumns = accepted
                                        leftSidebarResizing = true
                                    }
                                },
                                onResize = { requestedColumns ->
                                    if (leftSidebarResizing) {
                                        clampSessionSidebarResize(
                                            columns = columns,
                                            oppositeColumns = rightSidebarColumns,
                                            requestedColumns = requestedColumns,
                                        )?.let { accepted ->
                                            leftSidebarResizeColumns = accepted
                                        }
                                    }
                                },
                                onResizeEnd = { requestedColumns ->
                                    if (leftSidebarResizing) {
                                        val accepted = clampSessionSidebarResize(
                                            columns = columns,
                                            oppositeColumns = rightSidebarColumns,
                                            requestedColumns = requestedColumns,
                                        ) ?: leftSidebarResizeColumns
                                        leftSidebarResizing = false
                                        if (accepted != null) {
                                            leftSidebarResizeColumns = accepted
                                            if (accepted != sidebarConfiguration.leftWidth) {
                                                scope.launch {
                                                    try {
                                                        sidebarSettings.resizeLeft(accepted)
                                                    } catch (failure: CancellationException) {
                                                        throw failure
                                                    } catch (_: Throwable) {
                                                        if (leftSidebarResizeColumns == accepted) {
                                                            leftSidebarResizeColumns = null
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                            )
                        }
                        Box(modifier = Modifier.width(contentColumns).height(contentRows)) {
                            when (selected) {
                                is NewSessionViewModel -> {
                                    val draftSettings by selected.settings.collectAsState()
                                    NewSessionScreen(
                                        viewModel = selected,
                                        columns = contentColumns,
                                        rows = contentRows,
                                        newLineKey = currentNewLineKey,
                                        statusBarRows = newSessionStatusBarRows(contentColumns, draftSettings),
                                        onSubmit = {
                                            scope.launch { viewModel.materializeNewSession(selected) }
                                        },
                                        statusBar = {
                                            NewSessionStatusBar(
                                                columns = contentColumns,
                                                settings = draftSettings,
                                                runtimeConfiguration = selected.runtimeConfiguration,
                                                dropdowns = runtimeDropdowns,
                                                onBrowseWorkingDirectory = {
                                                    scope.launch { viewModel.openWorkingDirectoryPopup(selected) }
                                                },
                                                onOpenSettings = {
                                                    scope.launch {
                                                        viewModel.openSettingsPopup(selected, SettingsPage.CurrentSession)
                                                    }
                                                },
                                            )
                                        },
                                    )
                                }

                                is PersistedSessionViewModel -> selectedAgent?.let { agent ->
                                    key(agent) {
                                        AgentRuntimeScreen(
                                            viewModel = agent,
                                            columns = contentColumns,
                                            rows = contentRows,
                                            newLineKey = currentNewLineKey,
                                            dropdowns = runtimeDropdowns,
                                            suggestionDropdowns = suggestionDropdowns,
                                            composerFocusRequester = composerFocusRequester,
                                            onOpenHistoryEntryContextMenu = { generation, storageIndex, item, anchor, position ->
                                                tabMenu = null
                                                shellSessionMenu = null
                                                shellSessionHoverCloseJob?.cancel()
                                                shellSessionHover = null
                                                historyIndexHover = null
                                                historyIndexMenu = null
                                                historyMenu = HistoryEntryMenuRequest(
                                                    session = selected,
                                                    agent = agent,
                                                    generation = generation,
                                                    storageIndex = storageIndex,
                                                    item = item,
                                                    anchor = anchor,
                                                    clickPosition = position,
                                                )
                                            },
                                            onBrowseWorkingDirectory = {
                                                scope.launch {
                                                    viewModel.openWorkingDirectoryPopup(agent)
                                                }
                                            },
                                            onBrowseSuggestedWorkingDirectory = { callId ->
                                                scope.launch {
                                                    viewModel.openSuggestedWorkingDirectoryPopup(agent, callId)
                                                }
                                            },
                                            onOpenSettings = {
                                                scope.launch {
                                                    viewModel.openSettingsPopup(
                                                        selected,
                                                        SettingsPage.CurrentSession,
                                                    )
                                                }
                                            },
                                        )
                                    }
                                } ?: Text(
                                    when (val status = selectedLifecycle) {
                                        is io.github.stream29.kodex.app.session.contract.PersistedSessionLifecycleState.Failed -> status.detail
                                        io.github.stream29.kodex.app.session.contract.PersistedSessionLifecycleState.Closed -> "Session view closed."
                                        else -> "Loading Session…"
                                    },
                                )
                            }
                        }
                        if (rightSidebarColumns > 0) {
                            SessionSidebar(
                                side = SessionSidebarSide.Right,
                                content = sidebarConfiguration.right,
                                selectedAgent = selectedAgent,
                                dropdownState = rightSidebarDropdown,
                                columns = rightSidebarColumns,
                                rows = contentRows,
                                onHoverChanged = { rightSidebarSurfaceHovered = it },
                                onToggleExpanded = {
                                    rightSidebarPinnedExpanded = !rightSidebarPinnedExpanded
                                    if (!rightSidebarPinnedExpanded) {
                                        rightSidebarDropdown.dismiss()
                                        if (shellSessionMenu?.side == SessionSidebarSide.Right) {
                                            shellSessionMenu = null
                                        }
                                        if (shellSessionHover?.side == SessionSidebarSide.Right) {
                                            shellSessionHoverCloseJob?.cancel()
                                            shellSessionHover = null
                                        }
                                    }
                                },
                                onShellSessionHoverChanged = ::updateShellSessionRowHover,
                                onOpenShellSessionMenu = { request ->
                                    shellSessionHoverCloseJob?.cancel()
                                    shellSessionHover = null
                                    shellSessionPopupHovered = false
                                    tabMenu = null
                                    historyMenu = null
                                    historyIndexHover = null
                                    historyIndexMenu = null
                                    shellSessionMenu = SidebarShellSessionMenuRequest(
                                        side = SessionSidebarSide.Right,
                                        request = request,
                                    )
                                },
                                onHistoryIndexHoverChanged = ::updateHistoryIndexRowHover,
                                onOpenHistoryIndexMenu = { request ->
                                    shellSessionHoverCloseJob?.cancel()
                                    shellSessionHover = null
                                    shellSessionPopupHovered = false
                                    historyIndexHoverCloseJob?.cancel()
                                    historyIndexHover = null
                                    historyIndexPopupHovered = false
                                    tabMenu = null
                                    historyMenu = null
                                    shellSessionMenu = null
                                    historyIndexMenu = request
                                },
                                resizable = clampSessionSidebarResize(
                                    columns = columns,
                                    oppositeColumns = leftSidebarColumns,
                                    requestedColumns = rightSidebarColumns,
                                ) != null,
                                onResizeStart = { currentColumns ->
                                    val accepted = clampSessionSidebarResize(
                                        columns = columns,
                                        oppositeColumns = leftSidebarColumns,
                                        requestedColumns = currentColumns,
                                    )
                                    if (accepted != null) {
                                        rightSidebarResizeColumns = accepted
                                        rightSidebarResizing = true
                                    }
                                },
                                onResize = { requestedColumns ->
                                    if (rightSidebarResizing) {
                                        clampSessionSidebarResize(
                                            columns = columns,
                                            oppositeColumns = leftSidebarColumns,
                                            requestedColumns = requestedColumns,
                                        )?.let { accepted ->
                                            rightSidebarResizeColumns = accepted
                                        }
                                    }
                                },
                                onResizeEnd = { requestedColumns ->
                                    if (rightSidebarResizing) {
                                        val accepted = clampSessionSidebarResize(
                                            columns = columns,
                                            oppositeColumns = leftSidebarColumns,
                                            requestedColumns = requestedColumns,
                                        ) ?: rightSidebarResizeColumns
                                        rightSidebarResizing = false
                                        if (accepted != null) {
                                            rightSidebarResizeColumns = accepted
                                            if (accepted != sidebarConfiguration.rightWidth) {
                                                scope.launch {
                                                    try {
                                                        sidebarSettings.resizeRight(accepted)
                                                    } catch (failure: CancellationException) {
                                                        throw failure
                                                    } catch (_: Throwable) {
                                                        if (rightSidebarResizeColumns == accepted) {
                                                            rightSidebarResizeColumns = null
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                    if (showLeftSidebarExpandButton || showRightSidebarExpandButton) {
                        Row(modifier = Modifier.width(columns).height(SessionSidebarCollapsedButtonRows)) {
                            if (showLeftSidebarExpandButton) {
                                SessionSidebarExpandButton(
                                    side = SessionSidebarSide.Left,
                                    onHoverChanged = { hovered ->
                                        leftSidebarExpandButtonHovered = hovered &&
                                            canExpandSessionSidebar(
                                                columns = columns,
                                                requestedColumns =
                                                    sidebarConfiguration.leftWidth,
                                                oppositeColumns = if (rightSidebarExpanded) {
                                                    rightSidebarPreferredColumns
                                                } else {
                                                    0
                                                },
                                            )
                                    },
                                    onExpand = {
                                        if (
                                            canExpandSessionSidebar(
                                                columns = columns,
                                                requestedColumns =
                                                    sidebarConfiguration.leftWidth,
                                                oppositeColumns = if (rightSidebarExpanded) {
                                                    rightSidebarPreferredColumns
                                                } else {
                                                    0
                                                },
                                            )
                                        ) {
                                            leftSidebarPinnedExpanded = true
                                        }
                                    },
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (showRightSidebarExpandButton) {
                                SessionSidebarExpandButton(
                                    side = SessionSidebarSide.Right,
                                    onHoverChanged = { hovered ->
                                        rightSidebarExpandButtonHovered = hovered &&
                                            canExpandSessionSidebar(
                                                columns = columns,
                                                requestedColumns =
                                                    sidebarConfiguration.rightWidth,
                                                oppositeColumns = if (leftSidebarExpanded) {
                                                    leftSidebarPreferredColumns
                                                } else {
                                                    0
                                                },
                                            )
                                    },
                                    onExpand = {
                                        if (
                                            canExpandSessionSidebar(
                                                columns = columns,
                                                requestedColumns =
                                                    sidebarConfiguration.rightWidth,
                                                oppositeColumns = if (leftSidebarExpanded) {
                                                    leftSidebarPreferredColumns
                                                } else {
                                                    0
                                                },
                                            )
                                        ) {
                                            rightSidebarPinnedExpanded = true
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            ShellSessionHoverPopup(
                request = shellSessionHover.takeIf { shellSessionMenu == null },
                contentColumns = contentColumns,
                contentRows = contentRows,
                onHoverChanged = { hovered ->
                    shellSessionPopupHovered = hovered
                    if (hovered) {
                        shellSessionHoverCloseJob?.cancel()
                    } else {
                        closeShellSessionHoverAfterGrace()
                    }
                },
                onDismissRequest = {
                    shellSessionHoverCloseJob?.cancel()
                    shellSessionHover = null
                    shellSessionPopupHovered = false
                },
            )
            HistoryIndexHoverPopup(
                request = historyIndexHover.takeIf {
                    historyIndexMenu == null && it?.viewModel === selectedAgent?.historyIndex
                },
                contentColumns = contentColumns,
                contentRows = contentRows,
                onHoverChanged = { hovered ->
                    historyIndexPopupHovered = hovered
                    if (hovered) {
                        historyIndexHoverCloseJob?.cancel()
                    } else {
                        closeHistoryIndexHoverAfterGrace()
                    }
                },
                onDismissRequest = { historyIndexHover = null },
            )
            HistoryIndexContextMenu(
                request = historyIndexMenu.takeIf { it?.target?.viewModel === selectedAgent?.historyIndex },
                onDismissRequest = { historyIndexMenu = null },
            )
            ShellSessionContextMenu(shellSessionMenu?.request) { shellSessionMenu = null }
            SessionSidebarContentMenu(
                dropdownState = leftSidebarDropdown,
                selected = sidebarConfiguration.left,
                onSelect = { content ->
                    if (content != SidebarContent.TerminalSessions &&
                        shellSessionMenu?.side == SessionSidebarSide.Left
                    ) {
                        shellSessionMenu = null
                    }
                    if (content != SidebarContent.TerminalSessions &&
                        shellSessionHover?.side == SessionSidebarSide.Left
                    ) {
                        shellSessionHoverCloseJob?.cancel()
                        shellSessionHover = null
                    }
                    scope.launch { sidebarSettings.selectLeft(content) }
                },
            )
            SessionSidebarContentMenu(
                dropdownState = rightSidebarDropdown,
                selected = sidebarConfiguration.right,
                onSelect = { content ->
                    if (content != SidebarContent.TerminalSessions &&
                        shellSessionMenu?.side == SessionSidebarSide.Right
                    ) {
                        shellSessionMenu = null
                    }
                    if (content != SidebarContent.TerminalSessions &&
                        shellSessionHover?.side == SessionSidebarSide.Right
                    ) {
                        shellSessionHoverCloseJob?.cancel()
                        shellSessionHover = null
                    }
                    scope.launch { sidebarSettings.selectRight(content) }
                },
            )
            SessionTabContextMenu(
                request = tabMenu,
                targetIsOpen = navigation.tabs.any { it === tabMenu?.target },
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
                    try {
                        request.agent.requestHistoryRevert(request.storageIndex + 1, request.generation)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        // The Agent reports rejected history operations.
                    }
                },
                onRevertAndEdit = { request, text ->
                    historyMenu = null
                    scope.launch {
                        try {
                            revertAndEdit(request.agent, request.storageIndex, request.generation, text)
                            // Let the closed menu dispose before moving focus out of its restore target.
                            withFrameNanos { }
                            if (currentSelectedAgent === request.agent) {
                                currentComposerFocusRequester.requestFocus()
                            }
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Throwable) {
                            // Revert failures are reported by the Agent; the draft is left unchanged.
                        }
                    }
                },
                onFork = { request ->
                    historyMenu = null
                    scope.launch {
                        try {
                            val index = request.session.fork(
                                request.agent, request.storageIndex + 1, request.generation,
                            )
                            viewModel.openSession(index)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Throwable) {
                            // Fork and open each report at their owning boundary.
                        }
                    }
                },
            )
            AgentHistoryRevertDialog(selectedAgent)
            if (selectedAgent != null && pendingSuggestion != null) {
                SuggestSubagentTaskConfigurationMenus(
                    viewModel = selectedAgent.suggestSubagentTask,
                    state = pendingSuggestion,
                    dropdowns = suggestionDropdowns,
                )
            }
            if (runtimeConfiguration != null) {
                RuntimeConfigurationMenus(
                    viewModel = runtimeConfiguration,
                    dropdowns = runtimeDropdowns,
                )
            }

            when (val open = popup) {
                ApplicationPopupState.Closed -> Unit
                is ApplicationPopupState.SessionCatalog ->
                    SessionCatalogPopup(open.viewModel)

                is ApplicationPopupState.Settings -> TuiTheme(
                    colorScheme = tuiColorSchemeFor(terminal.theme),
                ) {
                    SettingsPopup(
                        viewModel = open.viewModel,
                        onDismissRequest = { viewModel.dismissPopup(open) },
                        onOpenLogin = {
                            scope.launch { viewModel.openLoginPopup(open) }
                        },
                    )
                }

                is ApplicationPopupState.RenameSession ->
                    SessionRenamePopup(
                        viewModel = open.viewModel,
                        onDismissRequest = { viewModel.dismissPopup(open) },
                        onSubmitted = { viewModel.dismissPopup(open) },
                    )

                is ApplicationPopupState.DeleteSession ->
                    SessionDeletePopup(
                        viewModel = open.viewModel,
                        onDismissRequest = { viewModel.dismissPopup(open) },
                        onResult = { viewModel.dismissPopup(open) },
                    )

                is ApplicationPopupState.Login -> OpenAiLoginPopup(
                    viewModel = open.viewModel,
                    onDismissRequest = { viewModel.dismissPopup(open) },
                )

                is ApplicationPopupState.WorkingDirectory -> WorkingDirectoryPopup(
                    viewModel = open.viewModel,
                    onDismissRequest = { viewModel.dismissPopup(open) },
                    onSelected = { viewModel.dismissPopup(open) },
                )
            }
        }
    }
}

/** Renderer-local retention only; departed handles never keep a tab identity alive. */
internal fun pruneSessionTabIdentities(
    identities: MutableMap<SessionViewModel, SessionTabIdentity>,
    openTabs: List<SessionViewModel>,
) {
    identities.keys.retainAll(openTabs.toSet())
}

private data class SidebarShellSessionMenuRequest(
    val side: SessionSidebarSide,
    val request: ShellSessionMenuRequest,
)

/** @param request null means no tab menu is open. */
@Composable
private fun BoxScope.SessionTabContextMenu(
    request: SessionTabMenuRequest?,
    targetIsOpen: Boolean,
    onDismiss: () -> Unit,
    onClose: (SessionViewModel) -> Unit,
    onCloseAndArchive: (PersistedSessionViewModel) -> Unit,
    onRename: (SessionViewModel) -> Unit,
    onDelete: (SessionViewModel) -> Unit,
) {
    val current = request ?: return
    val valid = targetIsOpen && current.anchor.isPlaced
    LaunchedEffect(current, valid) {
        if (!valid) onDismiss()
    }
    if (!valid) return
    val createdAt = rememberMenuTimestamp(current) {
        (current.target as? PersistedSessionViewModel)?.readCreatedAt()
    }
    val updatedAt = rememberMenuTimestamp(current) {
        (current.target as? PersistedSessionViewModel)?.readUpdatedAt()
    }
    SessionTabContextMenuPopup(
        target = current.target,
        createdAt = createdAt,
        updatedAt = updatedAt,
        anchor = current.anchor,
        clickPosition = current.clickPosition,
        onDismiss = onDismiss,
        onClose = { onClose(current.target) },
        onCloseAndArchive = onCloseAndArchive,
        onRename = { onRename(current.target) },
        onDelete = { onDelete(current.target) },
    )
}

/**
 * @param clickPosition null uses the keyboard anchor position.
 * @param createdAt null hides the creation field.
 * @param updatedAt null hides the updated field.
 */
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
    createdAt: String? = null,
    updatedAt: String? = null,
) {
    TuiContextMenu(
        expanded = true,
        anchor = anchor,
        clickPosition = clickPosition,
        onDismissRequest = onDismiss,
        backgroundColor = PopupMenuBackground,
    ) {
        if (target is PersistedSessionViewModel) {
            TuiPopupMenuItem(
                key = "session-index-information",
                onClick = {},
                enabled = false,
            ) {
                Column {
                    Text("Index: ${target.sessionIndex}")
                    TimestampInformation("Created at", createdAt)
                    TimestampInformation("Updated at", updatedAt)
                }
            }
        }
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
    onRevertAndEdit: (HistoryEntryMenuRequest, String) -> Unit,
    onFork: (HistoryEntryMenuRequest) -> Unit,
) {
    val current = request ?: return
    val state by current.agent.state.collectAsState()
    val running by current.agent.running.collectAsState()
    val historyItems by current.agent.history.historyItems.collectAsState()
    val targetMatches =
        current.session === selectedSession &&
            current.agent === selectedAgent &&
            state.canEditHistory(running) &&
            historyItems.size > 0 &&
            current.generation == historyItems.generation &&
            current.agent.history.contains(
                current.generation,
                current.storageIndex,
            )
    val anchorPlaced = current.anchor.isPlaced
    LaunchedEffect(current, targetMatches, anchorPlaced) {
        if (!targetMatches || !anchorPlaced) onDismiss()
    }
    if (!targetMatches || !anchorPlaced) return

    val message = current.item as? MessageHistoryItemViewModel
    val messageState = message?.state?.collectAsState()?.value
    val editText = messageState.revertAndEditText()
    val timestamp = rememberMenuTimestamp(current) { message?.readTimestamp() }
    HistoryEntryContextMenuPopup(
        anchor = current.anchor,
        clickPosition = current.clickPosition,
        onDismiss = onDismiss,
        onRevert = { onRevert(current) },
        onFork = { onFork(current) },
        onRevertAndEdit = editText?.let { text -> { onRevertAndEdit(current, text) } },
        messageIndex = message?.index,
        timestamp = timestamp,
    )
}

/**
 * @param clickPosition null uses the keyboard anchor position.
 * @param messageIndex null denotes a non-Message menu, without new information items.
 * @param timestamp null hides the timestamp field.
 */
@Composable
internal fun BoxScope.HistoryEntryContextMenuPopup(
    anchor: TuiPopupAnchor,
    clickPosition: IntOffset?,
    onDismiss: () -> Unit,
    onRevert: () -> Unit,
    onFork: () -> Unit,
    messageIndex: Int? = null,
    timestamp: String? = null,
    onRevertAndEdit: (() -> Unit)? = null,
) {
    TuiContextMenu(
        expanded = true,
        anchor = anchor,
        clickPosition = clickPosition,
        onDismissRequest = onDismiss,
        backgroundColor = PopupMenuBackground,
    ) {
        if (messageIndex != null) {
            TuiPopupMenuItem(key = "history-index-information", onClick = {}, enabled = false) {
                Column {
                    Text("Index: $messageIndex")
                    TimestampInformation("Timestamp", timestamp)
                }
            }
        }
        TuiPopupMenuItem(key = "revert-to-here", onClick = onRevert) {
            Text("Revert to here")
        }
        TuiPopupMenuItem(key = "fork-from-here", onClick = onFork) {
            Text("Fork from here")
        }
        if (onRevertAndEdit != null) {
            TuiPopupMenuItem(key = "revert-and-edit", onClick = onRevertAndEdit) {
                Text("Revert and edit")
            }
        }
    }
}

@Composable
internal fun BoxScope.AgentHistoryRevertDialog(agent: AgentViewModel?) {
    if (agent == null) return
    val action by agent.historyAction.collectAsState()
    val confirm =
        action as? AgentHistoryActionState.ConfirmRevert
            ?: return
    val state by agent.state.collectAsState()
    val running by agent.running.collectAsState()
    val historyItems by agent.history.historyItems.collectAsState()
    val targetMatches =
        state.canEditHistory(running) &&
            confirm.expectedGeneration == historyItems.generation
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
                        try {
                            agent.confirmHistoryRevert(confirm.requestId)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            // The Agent reports both rejected and failed reverts.
                        }
                    },
                )
            }
        }
    }
}

/** Each instance identifies a fresh opening, even for the same target and anchor. */
private class SessionTabMenuRequest(
    val target: SessionViewModel,
    val name: String,
    val anchor: TuiPopupAnchor,
    val clickPosition: IntOffset?,
)

/** Each instance identifies a fresh opening, even for the same target and anchor. */
/** Each instance identifies a fresh opening, even for the same target and anchor. */
private class HistoryEntryMenuRequest(
    val session: PersistedSessionViewModel,
    val agent: AgentViewModel,
    val generation: Long,
    val storageIndex: Int,
    val item: io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel,
    val anchor: TuiPopupAnchor,
    val clickPosition: IntOffset?,
)

/** Only original, entirely textual user content can round-trip through the main composer. */
internal fun MessageHistoryItemState?.revertAndEditText(): String? {
    val message = (this as? MessageHistoryItemState.Ready)?.event as? StableUserMessage ?: return null
    if (message.content.any { it is ContentItem.InputImage }) return null
    return message.content.joinToString("") { part ->
        when (part) {
            is ContentItem.InputText -> part.text
            is ContentItem.OutputText -> part.text
            is ContentItem.InputImage -> error("Image content cannot be edited in the text composer.")
        }
    }
}

/** Frontend composition of the existing history and composer capabilities, never an auto-submit. */
internal suspend fun revertAndEdit(
    agent: AgentViewModel,
    storageIndex: Int,
    generation: Long,
    text: String,
) {
    agent.revertHistory(storageIndex, generation)
    agent.composer.update(text)
}

private val PopupMenuBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainer

private const val SessionTabBarRows: Int = 1
private val SidebarHoverCloseGrace = 180.milliseconds
private const val RevertDialogWidth: Int = 56
