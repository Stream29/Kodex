package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.application.contract.*
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupFocus
import io.github.stream29.kodex.app.ui.rememberUiEditorFocusHandle
import io.github.stream29.kodex.cli.app.*
import io.github.stream29.kodex.cli.historyindex.LocalHistoryIndexNavigation
import io.github.stream29.kodex.cli.sessioncatalog.SessionGroupsContent
import io.github.stream29.kodex.cli.settings.MinimumSidebarWidthColumns
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SettingsHostCapabilities
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.cli.settings.ToolWindowId
import io.github.stream29.kodex.cli.settings.ToolWindowSide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlin.math.roundToInt

@Composable
public actual fun ComposeApplicationScreen(
    viewModel: ApplicationViewModel,
    newLineKey: StateFlow<NewLineKey>,
    sidebarSettings: SidebarSettingsViewModel,
    onOperationFailure: (Throwable) -> Unit,
    openExternalUrl: suspend (String) -> Boolean,
    capabilities: SettingsHostCapabilities,
) {
    CompositionLocalProvider(LocalGraphicalHostCapabilities provides capabilities) {
        key(GraphicalRootOwnerKey(viewModel), GraphicalRootOwnerKey(sidebarSettings)) {
            GraphicalApplication(viewModel, newLineKey, sidebarSettings, onOperationFailure, openExternalUrl)
        }
    }
}

@Composable
private fun GraphicalApplication(
    viewModel: ApplicationViewModel,
    newLineKey: StateFlow<NewLineKey>,
    sidebarSettings: SidebarSettingsViewModel,
    onOperationFailure: (Throwable) -> Unit,
    openExternalUrl: suspend (String) -> Boolean,
) {
    val frame = collectApplicationFrame(viewModel)
    val currentNewLineKey = newLineKey.collectAsState().value
    val configuration = sidebarSettings.state.collectAsState().value
    val actions = rememberApplicationRootActions(viewModel, onOperationFailure)
    val leftExpanded = configuration.activeLeft == ToolWindowId.Sessions
    val rightContent = when (configuration.activeRight) {
        ToolWindowId.HistoryIndex -> SidebarContent.HistoryIndex
        ToolWindowId.TerminalSessions -> SidebarContent.TerminalSessions
        else -> SidebarContent.None
    }
    val rightExpanded = rightContent != SidebarContent.None
    val overlays = rememberApplicationOverlays(frame.selectedAgent, SidebarContent.None, rightContent, frame.popup)
    val applicationMenuRenderer = rememberComposeApplicationMenuRenderer()
    val historyStates = remember { mutableListOf<Pair<AgentHistoryViewModel, ComposeHistoryViewState>>() }
    retainApplicationHistoryWidgetStates(
        historyStates, frame.tabs.tabs.mapNotNull { it.agent?.history },
    )
    val composerFocus = key(GraphicalRootOwnerKey(frame.selectedAgent)) { rememberUiEditorFocusHandle() }
    val report by rememberUpdatedState(onOperationFailure)
    val scope = rememberCoroutineScope()
    var rightMounted by remember { mutableStateOf(false) }
    val leftPreview = remember { mutableStateOf<Float?>(null) }
    val rightPreview = remember { mutableStateOf<Float?>(null) }
    val leftRailFocus = remember { FocusRequester() }
    val rightRailFocus = remember { FocusRequester() }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val measuredColumn = measurer.measure(AnnotatedString("0"), style = MaterialTheme.typography.bodyMedium)
    val columnPixels = (measuredColumn.getLineRight(0) - measuredColumn.getLineLeft(0)).coerceAtLeast(1f)

    fun command(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { report(failure) }
        }
    }
    fun focusRail(side: ToolWindowSide) {
        try {
            (if (side == ToolWindowSide.Left) leftRailFocus else rightRailFocus).requestFocus()
        } catch (_: IllegalStateException) { }
    }
    fun clearToolWindowOverlay(side: ToolWindowSide) {
        if (side == ToolWindowSide.Right) overlays.clearSide(SessionSidebarSide.Right)
    }
    fun collapse(side: ToolWindowSide) {
        clearToolWindowOverlay(side)
        command {
            sidebarSettings.collapse(side)
            focusRail(side)
        }
    }
    fun toggle(id: ToolWindowId) {
        clearToolWindowOverlay(id.side)
        command { sidebarSettings.toggle(id) }
    }
    val rightSide = SessionSidebarSide.Right
    val rightRenderer = rememberComposeSessionSidebarRenderer(
        showContentMenu = false,
        onShellHoverChanged = { origin, entered -> overlays.hoverChanged(ApplicationOverlayHover.Shell(rightSide, origin), entered) },
        onOpenShellMenu = { overlays.openMenu(ApplicationOverlayMenu.Shell(rightSide, it)) },
        onIndexHoverChanged = { origin, entered -> overlays.hoverChanged(ApplicationOverlayHover.Index(rightSide, origin), entered) },
        onOpenIndexMenu = { overlays.openMenu(ApplicationOverlayMenu.Index(rightSide, it)) },
    )

    val popupForFocus = rememberUpdatedState(frame.popup)
    val indexNavigation = rememberApplicationIndexNavigation(viewModel, frame, onOperationFailure)
    Surface(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalSidebarFocusRestoreAllowed provides {
                overlays.menu == null && popupForFocus.value == ApplicationPopupState.Closed
            },
            LocalHistoryIndexNavigation provides indexNavigation,
        ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp)) {
                    ApplicationTabBarContent(
                        viewModel, frame.navigation, frame.tabs, rememberComposeSessionTabBarRenderer(),
                        onOperationFailure, { overlays.openMenu(ApplicationOverlayMenu.Tab(it)) },
                        onBeforeNewOrCatalog = { overlays.clear() },
                        showSessions = false,
                    )
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val railWidth = minOf(40.dp, maxWidth / 4f)
                    val viewportPixels = with(density) { maxWidth.toPx() }
                    val viewportColumns = (viewportPixels / columnPixels).toInt().coerceAtLeast(1)
                    LaunchedEffect(sidebarSettings, viewportColumns) { sidebarSettings.initializeViewport(viewportColumns) }
                    val count = (if (leftExpanded) 1 else 0) + (if (rightExpanded) 1 else 0)
                    val splitterPixels = with(density) { GraphicalSidebarSplitterWidth.toPx() }
                    val minimumBody = minOf(with(density) { 320.dp.toPx() }, viewportPixels / 2f)
                    val railsPixels = with(density) { (railWidth * 2f).toPx() }
                    val sideBudget = (viewportPixels - railsPixels - minimumBody - count * splitterPixels).coerceAtLeast(0f)
                    val sideLimit = if (count == 0) 0f else sideBudget / count
                    val leftPixels = if (leftExpanded) {
                        (leftPreview.value ?: configuration.leftWidth * columnPixels).coerceIn(0f, sideLimit)
                    } else 0f
                    val rightPixels = if (rightExpanded) {
                        (rightPreview.value ?: configuration.rightWidth * columnPixels).coerceIn(0f, sideLimit)
                    } else 0f
                    Row(Modifier.fillMaxSize()) {
                        ComposeToolWindowRail(
                            ToolWindowSide.Left, configuration.activeLeft, ::toggle,
                            Modifier.width(railWidth).focusRequester(leftRailFocus),
                        )
                        if (leftPixels > 0f) {
                            Box(Modifier.width(with(density) { leftPixels.toDp() }).fillMaxHeight()
                                .semantics { contentDescription = "Left sidebar" }) {
                                val catalog = viewModel.sessionCatalog
                                if (catalog != null) {
                                    val focus = frame.selectedAgent?.let { agent ->
                                        val cwd = agent.settings.collectAsState().value.cwd.toString()
                                        frame.navigation.selectedTab?.let { SessionGroupFocus(cwd, it.agentId) }
                                    }
                                    SessionGroupsContent(
                                        catalog, focus, rememberComposeSessionGroupsRenderer(),
                                        onNewInGroup = { group ->
                                            overlays.clear()
                                            if (group == null) viewModel.selectNewSession()
                                            else viewModel.selectNewSessionInDirectory(Path(group))
                                        },
                                        onCollapse = { collapse(ToolWindowSide.Left) },
                                        onFailure = onOperationFailure,
                                    )
                                } else {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("Sessions unavailable", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                        ToolWindowIconButton("Collapse Sessions", ToolWindowIcon.Collapse, { collapse(ToolWindowSide.Left) })
                                    }
                                }
                            }
                            GraphicalSidebarSplitter(
                                SessionSidebarSide.Left, leftPixels, sideLimit, columnPixels, leftPreview,
                                enabled = frame.popup == ApplicationPopupState.Closed,
                            ) { columns -> command { sidebarSettings.resizeLeft(columns) } }
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "Session surface" }) {
                            ApplicationPageContent(
                                viewModel, frame.navigation, onOperationFailure,
                                newSession = { draft, submit ->
                                    val settings = draft.settings.collectAsState().value
                                    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                                        Column(
                                            Modifier.widthIn(max = 800.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("Start a new session", style = MaterialTheme.typography.headlineMedium)
                                                Text(
                                                    "Describe a task to work on together.",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    io.github.stream29.kodex.app.ui.WithAdaptiveEditor {
                                                        NewSessionDraft(draft.composer, currentNewLineKey, submit)
                                                    }
                                                    ApplicationDraftStatusContent(
                                                        settings, draft.runtimeConfiguration, rememberComposeAgentStatusRenderer(),
                                                        { actions.browseWorkingDirectory(draft) }, { actions.openSettings(null) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                },
                                agentTab = { selected ->
                                    ApplicationPersistedSurfaceContent(frame, agent = { agent ->
                                        val retained = applicationHistoryWidgetStateFor(historyStates, agent.history, ::ComposeHistoryViewState)
                                        val renderer = rememberComposeAgentPageRenderer(
                                            retained, composerFocus = composerFocus,
                                            onOpenHistoryContextMenu = { generation, index, item, placement ->
                                                val display = if (retained.canToggleStoredMarkdown(agent.history, generation, index, item)) {
                                                    ApplicationHistoryDisplayOption(
                                                        if (retained.markdownModes.storedIsRaw(generation, index)) "Show rendered" else "Show raw",
                                                    ) {
                                                        val current = overlays.menu as? ApplicationOverlayMenu.History
                                                        val opening = current?.opening
                                                        if (current != null && opening != null && opening.tab === selected && opening.agent === agent &&
                                                            opening.generation == generation && opening.storageIndex == index &&
                                                            opening.item === item && opening.placement === placement &&
                                                            viewModel.navigation.value.selectedTab === selected &&
                                                            selected.agent.value === agent &&
                                                            applicationMenuRenderer.isHistoryPlaced(placement) &&
                                                            retained.canToggleStoredMarkdown(agent.history, generation, index, item) &&
                                                            overlays.dismissMenu(current)
                                                        ) retained.markdownModes.toggleStored(generation, index)
                                                    }
                                                } else null
                                                overlays.openMenu(ApplicationOverlayMenu.History(ApplicationHistoryMenuOpening(
                                                    selected, agent, generation, index, item, placement, display,
                                                )))
                                            },
                                            onOpenStreamingHistoryContextMenu = { generation, item, placement ->
                                                if (retained.canToggleStreamingMarkdown(agent.history, generation, item)) {
                                                    overlays.openMenu(ApplicationOverlayMenu.StreamingHistory(
                                                        ApplicationStreamingHistoryMenuOpening(selected, agent, generation, item, placement),
                                                    ))
                                                }
                                            },
                                        )
                                        val pageTarget = rememberAgentPageActionTarget(selected, agent)
                                        AgentPageContent(
                                            agent, currentNewLineKey, renderer,
                                            { actions.browseAgentWorkingDirectory(pageTarget) },
                                            { actions.openAgentSettings(pageTarget) },
                                        )
                                    }, status = { Text(it, Modifier.padding(16.dp)) })
                                },
                            )
                        }
                        if (rightPixels > 0f) {
                            DisposableEffect(Unit) { rightMounted = true; onDispose { rightMounted = false } }
                            GraphicalSidebarSplitter(
                                SessionSidebarSide.Right, rightPixels, sideLimit, columnPixels, rightPreview,
                                enabled = frame.popup == ApplicationPopupState.Closed,
                            ) { columns -> command { sidebarSettings.resizeRight(columns) } }
                            Box(Modifier.width(with(density) { rightPixels.toDp() }).fillMaxHeight()
                                .semantics { contentDescription = "Right sidebar" }) {
                                val onCollapse = { collapse(ToolWindowSide.Right) }
                                if (frame.selectedAgent == null) {
                                    val label = if (rightContent == SidebarContent.HistoryIndex) "History Index" else "Terminal sessions"
                                    rightRenderer.Frame(rightSide, { rightRenderer.Header(rightSide, rightContent, label, onCollapse) }) {
                                        Text(
                                            "Select an Agent to use this tool window.", Modifier.padding(8.dp),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    SessionSidebarContent(rightSide, rightContent, frame.selectedAgent, rightRenderer, onCollapse)
                                }
                            }
                        }
                        ComposeToolWindowRail(
                            ToolWindowSide.Right, configuration.activeRight, ::toggle,
                            Modifier.width(railWidth).focusRequester(rightRailFocus),
                        )
                    }
                }
            }
            ApplicationSidebarOverlaysContent(
                overlays, frame.selectedAgent, rightRenderer, rememberComposeHistoryIndexRenderer(),
                isSideVisible = { side -> side == rightSide && rightExpanded && rightMounted },
                onShellOutputFallback = { focusRail(ToolWindowSide.Right) },
                application = viewModel,
                focusEditedComposer = { owner -> if (frame.selectedAgent === owner) composerFocus.requestFocus() },
                onOperationFailure = onOperationFailure,
            )
            ApplicationMenusContent(
                viewModel, frame.navigation, frame.selectedAgent,
                (overlays.menu as? ApplicationOverlayMenu.Tab)?.opening,
                (overlays.menu as? ApplicationOverlayMenu.History)?.opening,
                applicationMenuRenderer,
                admitTab = { expected ->
                    val current = overlays.menu as? ApplicationOverlayMenu.Tab
                    if (current?.opening === expected) overlays.dismissMenu(current) else false
                },
                admitHistory = { expected ->
                    val current = overlays.menu as? ApplicationOverlayMenu.History
                    if (current?.opening === expected) overlays.dismissMenu(current) else false
                },
                focusEditedComposer = { owner -> if (frame.selectedAgent === owner) composerFocus.requestFocus() },
                onOperationFailure = onOperationFailure,
            )
            val streamingMenu = overlays.menu as? ApplicationOverlayMenu.StreamingHistory
            if (streamingMenu != null) {
                val opening = streamingMenu.opening
                val widget = historyStates.firstOrNull { it.first === opening.agent.history }?.second
                val window = opening.agent.history.historyItems.collectAsState().value
                val streaming = opening.agent.history.streamingItem.collectAsState().value
                val valid = opening.tab === frame.navigation.selectedTab && opening.agent === frame.selectedAgent &&
                    window.generation == opening.generation && streaming is HistoryStreamingItem.Output &&
                    streaming.events === opening.item.events && widget != null &&
                    widget.canToggleStreamingMarkdown(opening.agent.history, opening.generation, opening.item) &&
                    applicationMenuRenderer.isHistoryPlaced(opening.placement)
                LaunchedEffect(streamingMenu, valid) { if (!valid) overlays.dismissMenu(streamingMenu) }
                if (valid) {
                    val raw = widget.markdownModes.streamIsRaw(opening.generation, opening.item.events)
                    applicationMenuRenderer.History(
                        opening.placement,
                        listOf(ApplicationMenuEntry.Action("history-display-mode", if (raw) "Show rendered" else "Show raw") {
                            if (overlays.menu === streamingMenu &&
                                viewModel.navigation.value.selectedTab === opening.tab &&
                                opening.tab.agent.value === opening.agent &&
                                applicationMenuRenderer.isHistoryPlaced(opening.placement) &&
                                widget.canToggleStreamingMarkdown(opening.agent.history, opening.generation, opening.item) &&
                                overlays.dismissMenu(streamingMenu)
                            ) widget.markdownModes.toggleStream(opening.generation, opening.item.events)
                        }),
                    ) { overlays.dismissMenu(streamingMenu) }
                }
            }
            ApplicationPopupContent(
                viewModel, frame.popup, rememberComposeApplicationPopupRenderer(openExternalUrl),
                onOperationFailure, openExternalUrl,
            )
        }
        }
    }
}

@Composable
private fun GraphicalSidebarSplitter(
    side: SessionSidebarSide, widthPixels: Float, maximumPixels: Float, columnPixels: Float,
    preview: MutableState<Float?>, enabled: Boolean, onCommit: (Int) -> Unit,
) {
    val width by rememberUpdatedState(widthPixels)
    val maximum by rememberUpdatedState(maximumPixels)
    val unit by rememberUpdatedState(columnPixels)
    val commit by rememberUpdatedState(onCommit)
    val admitted by rememberUpdatedState(enabled)
    var focused by remember { mutableStateOf(false) }
    fun resize(columns: Int): Boolean {
        if (!admitted || unit <= 0f || maximum <= 0f) return false
        val limit = (maximum / unit).toInt().coerceAtLeast(MinimumSidebarWidthColumns)
        preview.value = null
        commit(columns.coerceIn(MinimumSidebarWidthColumns, limit))
        return true
    }
    val columns = (widthPixels / columnPixels).roundToInt()
    val maximumColumns = (maximumPixels / columnPixels).toInt().coerceAtLeast(MinimumSidebarWidthColumns)
    LaunchedEffect(enabled) { if (!enabled) preview.value = null }
    DisposableEffect(preview) { onDispose { preview.value = null } }
    Box(
        Modifier.width(GraphicalSidebarSplitterWidth).fillMaxHeight()
            .onFocusChanged { focused = it.isFocused }
            .semantics {
                contentDescription = "Resize ${side.name.lowercase()} sidebar"
                stateDescription = "$columns columns"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    columns.coerceIn(MinimumSidebarWidthColumns, maximumColumns).toFloat(),
                    MinimumSidebarWidthColumns.toFloat()..maximumColumns.toFloat(),
                )
                if (!enabled) disabled()
                setProgress { if (it.isFinite()) resize(it.roundToInt()) else false }
            }
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown || !admitted) return@onPreviewKeyEvent false
                val direction = when (it.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> return@onPreviewKeyEvent false
                }
                val sideDirection = if (side == SessionSidebarSide.Left) direction else -direction
                resize((width / unit).roundToInt() + sideDirection)
            }
            .focusable(enabled)
            .pointerInput(side, enabled) {
                if (enabled) detectHorizontalDragGestures(
                    onDragStart = { preview.value = width },
                    onDragCancel = { preview.value = null },
                    onDragEnd = {
                        val pixels = (preview.value ?: width).coerceIn(0f, maximum)
                        preview.value = null
                        resize((pixels / unit).roundToInt())
                    },
                ) { change, delta ->
                    change.consume()
                    val direction = if (side == SessionSidebarSide.Left) 1 else -1
                    preview.value = ((preview.value ?: width) + direction * delta).coerceIn(0f, maximum)
                }
            },
    ) {
        Box(
            Modifier.width(1.dp).fillMaxHeight().align(Alignment.Center)
                .background(if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

private class GraphicalRootOwnerKey(private val owner: Any?) {
    override fun equals(other: Any?): Boolean = other is GraphicalRootOwnerKey && other.owner === owner
    override fun hashCode(): Int = 0
}

private val GraphicalSidebarSplitterWidth = 8.dp
internal val LocalGraphicalHostCapabilities = staticCompositionLocalOf { SettingsHostCapabilities() }
