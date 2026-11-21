package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationDropdowns
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationMenus
import io.github.stream29.kodex.cli.historyindex.HistoryIndexInteractionRequest
import io.github.stream29.kodex.cli.historyindex.HistoryIndexSide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.github.stream29.kodex.cli.historyindex.LocalHistoryIndexNavigation
import androidx.compose.runtime.LaunchedEffect
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
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationTabSelection
import io.github.stream29.kodex.app.application.contract.SidebarSettingsViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupFocus
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.cli.components.rememberRunningIndicatorFrame
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.tuiColorSchemeFor
import io.github.stream29.kodex.cli.sessioncatalog.SessionGroupsContent
import io.github.stream29.kodex.cli.sessioncatalog.rememberMosaicSessionGroupsRenderer
import io.github.stream29.kodex.cli.sessiontabbar.rememberMosaicSessionTabBarRenderer
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.cli.settings.ToolWindowId
import io.github.stream29.kodex.cli.settings.ToolWindowSide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

@Composable
public actual fun SessionTreeCliScreen(
    viewModel: ApplicationViewModel,
    newLineKey: StateFlow<NewLineKey>,
    sidebarSettings: SidebarSettingsViewModel,
    onOperationFailure: (Throwable) -> Unit,
    reservedRows: Int,
    openMcpUrl: suspend (String) -> Boolean,
): Unit = io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
    val terminal = LocalTerminalState.current
    val frame = collectApplicationFrame(viewModel)
    val navigation = frame.navigation
    val popup = frame.popup
    val rootActions = rememberApplicationRootActions(viewModel, onOperationFailure)
    val currentNewLineKey by newLineKey.collectAsState()
    val sidebarConfiguration by sidebarSettings.state.collectAsState()
    val tabProjection = frame.tabs
    val tabStates = tabProjection.tabs
    val selectedAgent = frame.selectedAgent
    val activeLeft = sidebarConfiguration.activeLeft
    val activeRight = sidebarConfiguration.activeRight
    // Existing right-hand renderers still consume their body kind, not preference state.
    val rightContent = when (activeRight) {
        ToolWindowId.HistoryIndex -> SidebarContent.HistoryIndex
        ToolWindowId.TerminalSessions -> SidebarContent.TerminalSessions
        null, ToolWindowId.Sessions -> SidebarContent.None
    }
    val overlays = rememberApplicationOverlays(selectedAgent, SidebarContent.None, rightContent, popup)
    val historyRenderStates = rememberHistoryRenderStates(tabStates)
    val agentSummary = summarizeOpenAgents(tabStates)
    val runningFrame = rememberRunningIndicatorFrame(
        active = agentSummary.runningAgentCount > 0,
    )
    TerminalTitleEffect(agentSummary.agentCount, agentSummary.runningAgentCount)

    val columns = terminal.size.columns.coerceAtLeast(1)
    val rows = (terminal.size.rows - 1 - reservedRows.coerceAtLeast(0)).coerceAtLeast(1)
    LaunchedEffect(sidebarSettings, columns) { sidebarSettings.initializeViewport(columns) }
    val scope = rememberCoroutineScope()
    var leftSidebarResizing by remember { mutableStateOf(false) }
    var leftSidebarResizeColumns by remember { mutableStateOf<Int?>(null) }
    var rightSidebarResizing by remember { mutableStateOf(false) }
    var rightSidebarResizeColumns by remember { mutableStateOf<Int?>(null) }
    val tabMenu = (overlays.menu as? ApplicationOverlayMenu.Tab)?.opening
    val historyMenu = (overlays.menu as? ApplicationOverlayMenu.History)?.opening
    val leftSidebarPreferredColumns =
        leftSidebarResizeColumns ?: sidebarConfiguration.leftWidth
    val rightSidebarPreferredColumns =
        rightSidebarResizeColumns ?: sidebarConfiguration.rightWidth
    val targetSidebarColumns = resolveSessionSidebarColumns(
        columns = columns,
        leftColumns = if (activeLeft != null) leftSidebarPreferredColumns else 0,
        rightColumns = if (activeRight != null) rightSidebarPreferredColumns else 0,
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
        leftColumns = if (activeLeft != null) leftSidebarResizeColumns ?: animatedLeftSidebarColumns else 0,
        rightColumns = if (activeRight != null) rightSidebarResizeColumns ?: animatedRightSidebarColumns else 0,
    )
    val leftSidebarColumns = displayedSidebarColumns.left
    val rightSidebarColumns = displayedSidebarColumns.right
    val contentColumns = displayedSidebarColumns.content
    val contentRows = (rows - ApplicationTopBarRows).coerceAtLeast(0)
    val leftPanelVisible = leftSidebarColumns > SessionSidebarSplitterColumns && contentRows > 0
    val rightPanelVisible = rightSidebarColumns > SessionSidebarSplitterColumns && contentRows > 0
    val currentSelectedAgent by rememberUpdatedState(selectedAgent)
    val currentApplicationPopup by rememberUpdatedState(popup)
    val composerFocusRequester = remember(selectedAgent) { FocusRequester() }
    val currentComposerFocusRequester by rememberUpdatedState(composerFocusRequester)
    val settingsOwner: AgentSettingsViewModel? =
        if (navigation.selection == ApplicationTabSelection.NewSession) viewModel.newSession else selectedAgent
    val runtimeConfiguration = when (settingsOwner) {
        is AgentViewModel -> settingsOwner.runtimeConfiguration
        is NewSessionViewModel -> settingsOwner.runtimeConfiguration
        else -> null
    }
    val runtimeDropdowns = RuntimeConfigurationDropdowns.remember(runtimeConfiguration)

    fun runWindowCommand(command: suspend () -> Unit) {
        scope.launch {
            try {
                command()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                onOperationFailure(failure)
            }
        }
    }

    fun collapseWindow(side: ToolWindowSide) {
        if (side == ToolWindowSide.Right) overlays.clearSide(SessionSidebarSide.Right)
        runWindowCommand { sidebarSettings.collapse(side) }
    }

    fun updateShellSessionRowHover(
        request: ShellSessionInteractionRequest,
        hovered: Boolean,
    ) {
        overlays.hoverChanged(
            ApplicationOverlayHover.Shell(request.side, mosaicShellHover(request), request), hovered,
        )
    }

    fun updateHistoryIndexRowHover(
        request: HistoryIndexInteractionRequest,
        hovered: Boolean,
    ) {
        val side = if (request.side == HistoryIndexSide.Left) SessionSidebarSide.Left else SessionSidebarSide.Right
        overlays.hoverChanged(ApplicationOverlayHover.Index(side, mosaicIndexHover(request), request), hovered)
    }

    LaunchedEffect(
        leftSidebarResizing,
        leftSidebarResizeColumns,
        sidebarConfiguration.leftWidth,
        leftPanelVisible,
        animatedLeftSidebarColumns,
        targetSidebarColumns.left,
    ) {
        if (!leftPanelVisible) {
            leftSidebarResizing = false
            leftSidebarResizeColumns = null
            return@LaunchedEffect
        }
        val resized = leftSidebarResizeColumns ?: return@LaunchedEffect
        if (!leftSidebarResizing && sidebarConfiguration.leftWidth == resized &&
            animatedLeftSidebarColumns == targetSidebarColumns.left
        ) {
            leftSidebarResizeColumns = null
        }
    }
    LaunchedEffect(
        rightSidebarResizing,
        rightSidebarResizeColumns,
        sidebarConfiguration.rightWidth,
        rightPanelVisible,
        animatedRightSidebarColumns,
        targetSidebarColumns.right,
    ) {
        if (!rightPanelVisible) {
            rightSidebarResizing = false
            rightSidebarResizeColumns = null
            return@LaunchedEffect
        }
        val resized = rightSidebarResizeColumns ?: return@LaunchedEffect
        if (!rightSidebarResizing && sidebarConfiguration.rightWidth == resized &&
            animatedRightSidebarColumns == targetSidebarColumns.right
        ) {
            rightSidebarResizeColumns = null
        }
    }

    val indexNavigation = rememberApplicationIndexNavigation(viewModel, frame, onOperationFailure)
    CompositionLocalProvider(LocalHistoryIndexNavigation provides indexNavigation) {
    TuiTheme(colorScheme = tuiColorSchemeFor(terminal.theme)) {
        TuiPopupHost {
            val menuRenderer = remember(this) { MosaicApplicationMenuRenderer(this) }
            val groupsRenderer = rememberMosaicSessionGroupsRenderer(
                columns = (leftSidebarColumns - SessionSidebarSplitterColumns).coerceAtLeast(0),
            )
            Column(modifier = Modifier.width(columns).height(rows)) {
                ApplicationTabBarContent(
                    viewModel = viewModel,
                    navigation = navigation,
                    projection = tabProjection,
                    renderer = rememberMosaicSessionTabBarRenderer(columns, runningFrame),
                    onOperationFailure = onOperationFailure,
                    showSessions = false,
                    onBeforeNewOrCatalog = { overlays.clear() },
                    onOpenTabMenu = { request ->
                        overlays.openMenu(ApplicationOverlayMenu.Tab(request))
                    },
                )
                if (rows >= ApplicationTopBarRows) {
                    ToolWindowBar(terminal.size.columns, activeLeft, activeRight, onToggle = { id ->
                        if (id.side == ToolWindowSide.Right) overlays.clearSide(SessionSidebarSide.Right)
                        runWindowCommand { sidebarSettings.toggle(id) }
                    })
                }
                Box(modifier = Modifier.width(columns).height(contentRows)) {
                    Row(modifier = Modifier.width(columns).height(contentRows)) {
                        if (leftSidebarColumns > 0) {
                            SessionSidebarFrame(
                                side = SessionSidebarSide.Left,
                                columns = leftSidebarColumns,
                                rows = contentRows,
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
                            ) { panelColumns ->
                                if (leftPanelVisible) {
                                    val catalog = viewModel.sessionCatalog
                                    if (catalog == null) Text("Sessions unavailable", Modifier.width(panelColumns))
                                    else {
                                        val selectedTab = navigation.selectedTab
                                        val focus = if (selectedAgent != null && selectedTab != null) {
                                            key(selectedAgent.settings) {
                                                val agentSettings by selectedAgent.settings.collectAsState()
                                                SessionGroupFocus(
                                                    cwd = agentSettings.cwd.toString(),
                                                    agentId = selectedTab.agentId,
                                                )
                                            }
                                        } else null
                                        SessionGroupsContent(
                                            viewModel = catalog,
                                            focus = focus,
                                            renderer = groupsRenderer,
                                            onNewInGroup = { cwd ->
                                                overlays.clear()
                                                if (cwd == null) viewModel.selectNewSession()
                                                else viewModel.selectNewSessionInDirectory(Path(cwd))
                                            },
                                            onCollapse = { collapseWindow(ToolWindowSide.Left) },
                                            onFailure = onOperationFailure,
                                        )
                                    }
                                }
                            }
                        }
                        Box(modifier = Modifier.width(contentColumns).height(contentRows)) {
                            ApplicationPageContent(
                                viewModel, navigation, onOperationFailure,
                                newSession = { draft, submit ->
                                    val draftSettings by draft.settings.collectAsState()
                                    NewSessionScreen(
                                        viewModel = draft,
                                        columns = contentColumns,
                                        rows = contentRows,
                                        newLineKey = currentNewLineKey,
                                        statusBarRows = { width -> newSessionStatusBarRows(width, draftSettings) },
                                        onSubmit = submit,
                                        statusBar = { width ->
                                            NewSessionStatusBar(
                                                columns = width,
                                                settings = draftSettings,
                                                runtimeConfiguration = draft.runtimeConfiguration,
                                                dropdowns = runtimeDropdowns,
                                                onBrowseWorkingDirectory = {
                                                    rootActions.browseWorkingDirectory(draft)
                                                },
                                                onOpenSettings = {
                                                    rootActions.openSettings(null)
                                                },
                                            )
                                        },
                                    )
                                },
                                agentTab = { selected ->
                                    ApplicationPersistedSurfaceContent(frame, agent = { agent ->
                                        val pageTarget = rememberAgentPageActionTarget(selected, agent)
                                        AgentRuntimeScreen(
                                            viewModel = agent,
                                            historyViewState = historyRenderStateFor(historyRenderStates, agent.history),
                                            columns = contentColumns,
                                            rows = contentRows,
                                            newLineKey = currentNewLineKey,
                                            dropdowns = runtimeDropdowns,
                                            composerFocusRequester = composerFocusRequester,
                                            onOpenHistoryEntryContextMenu = { generation, storageIndex, item, anchor, position ->
                                                overlays.openMenu(ApplicationOverlayMenu.History(ApplicationHistoryMenuOpening(
                                                    tab = selected,
                                                    agent = agent,
                                                    generation = generation,
                                                    storageIndex = storageIndex,
                                                    item = item,
                                                    placement = MosaicHistoryMenuPlacement(anchor, position),
                                                )))
                                            },
                                            onBrowseWorkingDirectory = {
                                                rootActions.browseAgentWorkingDirectory(pageTarget)
                                            },
                                            onOpenSettings = {
                                                rootActions.openAgentSettings(pageTarget)
                                            },
                                        )
                                    }, status = { Text(it) })
                                },
                            )
                        }
                        if (rightSidebarColumns > 0) {
                            SessionSidebar(
                                side = SessionSidebarSide.Right,
                                content = rightContent,
                                selectedAgent = selectedAgent,
                                columns = rightSidebarColumns,
                                rows = contentRows,
                                onToggleExpanded = { collapseWindow(ToolWindowSide.Right) },
                                onShellSessionHoverChanged = ::updateShellSessionRowHover,
                                onOpenShellSessionMenu = { request ->
                                    overlays.openMenu(ApplicationOverlayMenu.Shell(SessionSidebarSide.Right, mosaicShellMenu(request)))
                                },
                                onHistoryIndexHoverChanged = ::updateHistoryIndexRowHover,
                                onOpenHistoryIndexMenu = { request ->
                                    overlays.openMenu(ApplicationOverlayMenu.Index(SessionSidebarSide.Right, mosaicIndexMenu(request)))
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
                }
            }

            ApplicationSidebarOverlaysContent(
                overlays, selectedAgent,
                rememberMosaicSidebarOverlayRenderer(contentColumns, contentRows),
                rememberMosaicIndexOverlayRenderer(contentColumns, contentRows),
                isSideVisible = { side -> side == SessionSidebarSide.Right && rightPanelVisible },
                onShellOutputFallback = {
                    val owner = currentSelectedAgent
                    scope.launch {
                        withFrameNanos { }
                        if (overlays.menu == null && currentApplicationPopup == ApplicationPopupState.Closed &&
                            currentSelectedAgent === owner
                        ) currentComposerFocusRequester.requestFocus()
                    }
                },
                application = viewModel,
                focusEditedComposer = { owner ->
                    if (currentSelectedAgent === owner) currentComposerFocusRequester.requestFocus()
                },
                onOperationFailure = onOperationFailure,
            )
            groupsRenderer.Overlays()
            ApplicationMenusContent(
                viewModel = viewModel,
                navigation = navigation,
                selectedAgent = selectedAgent,
                tabOpening = tabMenu,
                historyOpening = historyMenu,
                renderer = menuRenderer,
                admitTab = { expected ->
                    val current = overlays.menu as? ApplicationOverlayMenu.Tab
                    if (current?.opening === expected) overlays.dismissMenu(current) else false
                },
                admitHistory = { expected ->
                    val current = overlays.menu as? ApplicationOverlayMenu.History
                    if (current?.opening === expected) overlays.dismissMenu(current) else false
                },
                focusEditedComposer = { owner ->
                    if (currentSelectedAgent === owner) currentComposerFocusRequester.requestFocus()
                },
                onOperationFailure = onOperationFailure,
            )
            if (runtimeConfiguration != null) {
                RuntimeConfigurationMenus(
                    viewModel = runtimeConfiguration,
                    dropdowns = runtimeDropdowns,
                )
            }

            ApplicationPopupContent(
                viewModel, popup, rememberMosaicApplicationPopupRenderer(), onOperationFailure, openMcpUrl,
            )
        }
    }
    }
}

private const val ApplicationTopBarRows: Int = 2
