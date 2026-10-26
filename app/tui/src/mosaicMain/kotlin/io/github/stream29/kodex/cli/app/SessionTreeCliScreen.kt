package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationDropdowns
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationMenus
import io.github.stream29.kodex.cli.historyindex.HistoryIndexInteractionRequest
import io.github.stream29.kodex.cli.historyindex.HistoryIndexSide

import io.github.stream29.kodex.cli.agent.SuggestSubagentTaskDropdowns
import io.github.stream29.kodex.cli.agent.SuggestSubagentTaskConfigurationMenus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.animation.animateIntAsState
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationTabSelection
import io.github.stream29.kodex.app.application.contract.SidebarSettingsViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.cli.components.rememberRunningIndicatorFrame
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.tuiColorSchemeFor
import io.github.stream29.kodex.cli.sessiontabbar.rememberMosaicSessionTabBarRenderer
import io.github.stream29.kodex.cli.settings.NewLineKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
    val selectedPersisted = navigation.selectedSession
    val selectedAgent = frame.selectedAgent
    val overlays = rememberApplicationOverlays(
        selectedAgent, sidebarConfiguration.left, sidebarConfiguration.right, popup,
    )
    val historyRenderStates = rememberHistoryRenderStates(tabStates)
    val sessionSummary = summarizeOpenSessions(tabStates)
    val runningFrame = rememberRunningIndicatorFrame(
        active = sessionSummary.runningSessionCount > 0,
    )
    TerminalTitleEffect(sessionSummary.sessionCount, sessionSummary.runningSessionCount)

    val columns = (terminal.size.columns - 1).coerceAtLeast(1)
    val rows = (terminal.size.rows - 1 - reservedRows.coerceAtLeast(0)).coerceAtLeast(1)
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
    val tabMenu = (overlays.menu as? ApplicationOverlayMenu.Tab)?.opening
    val historyMenu = (overlays.menu as? ApplicationOverlayMenu.History)?.opening
    val leftSidebarExpanded = leftSidebarPinnedExpanded ||
        leftSidebarExpandButtonHovered ||
        leftSidebarSurfaceHovered ||
        leftSidebarResizing ||
        leftSidebarDropdown.expanded ||
        (overlays.menu as? ApplicationOverlayMenu.Sidebar)?.side == SessionSidebarSide.Left ||
        overlays.hover?.side == SessionSidebarSide.Left
    val rightSidebarExpanded = rightSidebarPinnedExpanded ||
        rightSidebarExpandButtonHovered ||
        rightSidebarSurfaceHovered ||
        rightSidebarResizing ||
        rightSidebarDropdown.expanded ||
        (overlays.menu as? ApplicationOverlayMenu.Sidebar)?.side == SessionSidebarSide.Right ||
        overlays.hover?.side == SessionSidebarSide.Right
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
    val currentSelectedAgent by rememberUpdatedState(selectedAgent)
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
    val pendingSuggestion = selectedAgent?.let { agent ->
        key(agent) { agent.suggestSubagentTask.state.collectAsState().value }
    } as? io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState.Pending
    val suggestionDropdowns = SuggestSubagentTaskDropdowns.remember(
        selectedAgent?.suggestSubagentTask, pendingSuggestion?.callId,
    )

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
            val menuRenderer = remember(this) { MosaicApplicationMenuRenderer(this) }
            Column(modifier = Modifier.width(columns).height(rows)) {
                ApplicationTabBarContent(
                    viewModel = viewModel,
                    navigation = navigation,
                    projection = tabProjection,
                    renderer = rememberMosaicSessionTabBarRenderer(columns, runningFrame),
                    onOperationFailure = onOperationFailure,
                    onBeforeNewOrCatalog = { overlays.clear() },
                    onOpenTabMenu = { request ->
                        overlays.openMenu(ApplicationOverlayMenu.Tab(request))
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
                                        overlays.clearSide(SessionSidebarSide.Left)
                                    }
                                },
                                onShellSessionHoverChanged = ::updateShellSessionRowHover,
                                onOpenShellSessionMenu = { request ->
                                    overlays.openMenu(ApplicationOverlayMenu.Shell(SessionSidebarSide.Left, mosaicShellMenu(request)))
                                },
                                onHistoryIndexHoverChanged = ::updateHistoryIndexRowHover,
                                onOpenHistoryIndexMenu = { request ->
                                    overlays.openMenu(ApplicationOverlayMenu.Index(SessionSidebarSide.Left, mosaicIndexMenu(request)))
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
                                                        resizeApplicationSidebar(sidebarSettings, SessionSidebarSide.Left, accepted)
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
                            ApplicationPageContent(
                                viewModel, navigation, onOperationFailure,
                                newSession = { draft, submit ->
                                    val draftSettings by draft.settings.collectAsState()
                                    NewSessionScreen(
                                        viewModel = draft,
                                        columns = contentColumns,
                                        rows = contentRows,
                                        newLineKey = currentNewLineKey,
                                        statusBarRows = newSessionStatusBarRows(contentColumns, draftSettings),
                                        onSubmit = submit,
                                        statusBar = {
                                            NewSessionStatusBar(
                                                columns = contentColumns,
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
                                persistedSession = { selected ->
                                    ApplicationPersistedSurfaceContent(frame, agent = { agent ->
                                        AgentRuntimeScreen(
                                            viewModel = agent,
                                            historyViewState = historyRenderStateFor(historyRenderStates, agent.history),
                                            columns = contentColumns,
                                            rows = contentRows,
                                            newLineKey = currentNewLineKey,
                                            dropdowns = runtimeDropdowns,
                                            suggestionDropdowns = suggestionDropdowns,
                                            composerFocusRequester = composerFocusRequester,
                                            onOpenHistoryEntryContextMenu = { generation, storageIndex, item, anchor, position ->
                                                overlays.openMenu(ApplicationOverlayMenu.History(ApplicationHistoryMenuOpening(
                                                    session = selected,
                                                    agent = agent,
                                                    generation = generation,
                                                    storageIndex = storageIndex,
                                                    item = item,
                                                    placement = MosaicHistoryMenuPlacement(anchor, position),
                                                )))
                                            },
                                            onBrowseWorkingDirectory = {
                                                rootActions.browseWorkingDirectory(agent)
                                            },
                                            onBrowseSuggestedWorkingDirectory = { callId ->
                                                rootActions.browseSuggestedWorkingDirectory(agent, callId)
                                            },
                                            onOpenSettings = {
                                                rootActions.openSettings(selected)
                                            },
                                        )
                                    }, status = { Text(it) })
                                },
                            )
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
                                        overlays.clearSide(SessionSidebarSide.Right)
                                    }
                                },
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
                                                        resizeApplicationSidebar(sidebarSettings, SessionSidebarSide.Right, accepted)
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

            ApplicationSidebarOverlaysContent(
                overlays, selectedAgent,
                rememberMosaicSidebarOverlayRenderer(contentColumns, contentRows),
                rememberMosaicIndexOverlayRenderer(contentColumns, contentRows),
            )
            SessionSidebarContentMenu(
                dropdownState = leftSidebarDropdown,
                selected = sidebarConfiguration.left,
                onSelect = { content ->
                    overlays.clearSide(SessionSidebarSide.Left)
                    scope.launch { selectApplicationSidebarContent(sidebarSettings, SessionSidebarSide.Left, content) }
                },
            )
            SessionSidebarContentMenu(
                dropdownState = rightSidebarDropdown,
                selected = sidebarConfiguration.right,
                onSelect = { content ->
                    overlays.clearSide(SessionSidebarSide.Right)
                    scope.launch { selectApplicationSidebarContent(sidebarSettings, SessionSidebarSide.Right, content) }
                },
            )
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

            ApplicationPopupContent(
                viewModel, popup, rememberMosaicApplicationPopupRenderer(), onOperationFailure, openMcpUrl,
            )
        }
    }
}

private const val SessionTabBarRows: Int = 1
