package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.application.contract.*
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.ui.rememberUiEditorFocusHandle
import io.github.stream29.kodex.cli.app.*
import io.github.stream29.kodex.cli.settings.MinimumSidebarWidthColumns
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SettingsHostCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
    val overlays = rememberApplicationOverlays(frame.selectedAgent, configuration.left, configuration.right, frame.popup)
    val historyStates = remember { mutableListOf<Pair<AgentHistoryViewModel, ComposeHistoryViewState>>() }
    retainApplicationHistoryWidgetStates(historyStates, frame.tabs.tabs.mapNotNull { it.agent?.history })
    val composerFocus = key(GraphicalRootOwnerKey(frame.selectedAgent)) { rememberUiEditorFocusHandle() }
    val report by rememberUpdatedState(onOperationFailure)
    val scope = rememberCoroutineScope()
    var leftExpanded by remember { mutableStateOf(false) }
    var rightExpanded by remember { mutableStateOf(false) }
    val leftPreview = remember { mutableStateOf<Float?>(null) }
    val rightPreview = remember { mutableStateOf<Float?>(null) }
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
    fun collapse(side: SessionSidebarSide) {
        overlays.clearSide(side)
        when (side) {
            SessionSidebarSide.Left -> { leftExpanded = false; leftPreview.value = null }
            SessionSidebarSide.Right -> { rightExpanded = false; rightPreview.value = null }
        }
    }
    @Composable
    fun sidebarRenderer(side: SessionSidebarSide) = rememberComposeSessionSidebarRenderer(
        onSelectContent = { selectedSide, content ->
            overlays.clearSide(selectedSide)
            command { selectApplicationSidebarContent(sidebarSettings, selectedSide, content) }
        },
        onShellHoverChanged = { origin, entered -> overlays.hoverChanged(ApplicationOverlayHover.Shell(side, origin), entered) },
        onOpenShellMenu = { overlays.openMenu(ApplicationOverlayMenu.Shell(side, it)) },
        onIndexHoverChanged = { origin, entered -> overlays.hoverChanged(ApplicationOverlayHover.Index(side, origin), entered) },
        onOpenIndexMenu = { overlays.openMenu(ApplicationOverlayMenu.Index(side, it)) },
    )
    val leftRenderer = sidebarRenderer(SessionSidebarSide.Left)
    val rightRenderer = sidebarRenderer(SessionSidebarSide.Right)

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { if (leftExpanded) collapse(SessionSidebarSide.Left) else leftExpanded = true },
                        modifier = Modifier.semantics {
                            contentDescription = if (leftExpanded) "Hide left sidebar" else "Expand left sidebar"
                        },
                    ) { Text(if (leftExpanded) "‹" else "›") }
                    Box(Modifier.weight(1f)) {
                        ApplicationTabBarContent(
                            viewModel, frame.navigation, frame.tabs, rememberComposeSessionTabBarRenderer(),
                            onOperationFailure, { overlays.openMenu(ApplicationOverlayMenu.Tab(it)) },
                            onBeforeNewOrCatalog = { overlays.clear() },
                        )
                    }
                    TextButton(
                        onClick = { if (rightExpanded) collapse(SessionSidebarSide.Right) else rightExpanded = true },
                        modifier = Modifier.semantics {
                            contentDescription = if (rightExpanded) "Hide right sidebar" else "Expand right sidebar"
                        },
                    ) { Text(if (rightExpanded) "›" else "‹") }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val viewportPixels = with(density) { maxWidth.toPx() }
                    val viewportColumns = (viewportPixels / columnPixels).toInt().coerceAtLeast(1)
                    LaunchedEffect(sidebarSettings, viewportColumns) { sidebarSettings.initializeViewport(viewportColumns) }
                    val count = (if (leftExpanded) 1 else 0) + (if (rightExpanded) 1 else 0)
                    val splitterPixels = with(density) { GraphicalSidebarSplitterWidth.toPx() }
                    val minimumBody = minOf(with(density) { 320.dp.toPx() }, viewportPixels / 2f)
                    val sideBudget = (viewportPixels - minimumBody - count * splitterPixels).coerceAtLeast(0f)
                    val sideLimit = if (count == 0) 0f else sideBudget / count
                    val leftPixels = if (leftExpanded) {
                        (leftPreview.value ?: configuration.leftWidth * columnPixels).coerceIn(0f, sideLimit)
                    } else 0f
                    val rightPixels = if (rightExpanded) {
                        (rightPreview.value ?: configuration.rightWidth * columnPixels).coerceIn(0f, sideLimit)
                    } else 0f
                    Row(Modifier.fillMaxSize()) {
                        if (leftPixels > 0f) {
                            Box(Modifier.width(with(density) { leftPixels.toDp() }).fillMaxHeight()
                                .semantics { contentDescription = "Left sidebar" }) {
                                SessionSidebarContent(
                                    SessionSidebarSide.Left, configuration.left, frame.selectedAgent, leftRenderer,
                                    { collapse(SessionSidebarSide.Left) },
                                )
                            }
                            GraphicalSidebarSplitter(
                                SessionSidebarSide.Left, leftPixels, sideLimit, columnPixels, leftPreview,
                                enabled = frame.popup == ApplicationPopupState.Closed,
                            ) { columns -> command { resizeApplicationSidebar(sidebarSettings, SessionSidebarSide.Left, columns) } }
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "Session surface" }) {
                            ApplicationPageContent(
                                viewModel, frame.navigation, onOperationFailure,
                                newSession = { draft, submit ->
                                    val settings = draft.settings.collectAsState().value
                                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                        Column(
                                            Modifier.widthIn(max = 800.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                                            verticalArrangement = Arrangement.spacedBy(24.dp),
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("Start a new session", style = MaterialTheme.typography.headlineMedium)
                                                Text(
                                                    "Describe a task to work on together.",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                                    NewSessionDraft(draft.composer, currentNewLineKey, submit)
                                                    ApplicationDraftStatusContent(
                                                        settings, draft.runtimeConfiguration, rememberComposeAgentStatusRenderer(),
                                                        { actions.browseWorkingDirectory(draft) }, { actions.openSettings(null) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                },
                                persistedSession = { selected ->
                                    ApplicationPersistedSurfaceContent(frame, agent = { agent ->
                                        val retained = applicationHistoryWidgetStateFor(historyStates, agent.history, ::ComposeHistoryViewState)
                                        val renderer = rememberComposeAgentPageRenderer(
                                            retained, composerFocus = composerFocus,
                                            onOpenHistoryContextMenu = { generation, index, item, placement ->
                                                overlays.openMenu(ApplicationOverlayMenu.History(ApplicationHistoryMenuOpening(
                                                    selected, agent, generation, index, item, placement,
                                                )))
                                            },
                                        )
                                        AgentPageContent(
                                            agent, currentNewLineKey, renderer,
                                            { actions.browseWorkingDirectory(agent) },
                                            { actions.browseSuggestedWorkingDirectory(agent, it) },
                                            { actions.openSettings(selected) },
                                        )
                                    }, status = { Text(it, Modifier.padding(16.dp)) })
                                },
                            )
                        }
                        if (rightPixels > 0f) {
                            GraphicalSidebarSplitter(
                                SessionSidebarSide.Right, rightPixels, sideLimit, columnPixels, rightPreview,
                                enabled = frame.popup == ApplicationPopupState.Closed,
                            ) { columns -> command { resizeApplicationSidebar(sidebarSettings, SessionSidebarSide.Right, columns) } }
                            Box(Modifier.width(with(density) { rightPixels.toDp() }).fillMaxHeight()
                                .semantics { contentDescription = "Right sidebar" }) {
                                SessionSidebarContent(
                                    SessionSidebarSide.Right, configuration.right, frame.selectedAgent, rightRenderer,
                                    { collapse(SessionSidebarSide.Right) },
                                )
                            }
                        }
                    }
                }
            }
            ApplicationSidebarOverlaysContent(
                overlays, frame.selectedAgent, leftRenderer, rememberComposeHistoryIndexRenderer(),
            )
            ApplicationMenusContent(
                viewModel, frame.navigation, frame.selectedAgent,
                (overlays.menu as? ApplicationOverlayMenu.Tab)?.opening,
                (overlays.menu as? ApplicationOverlayMenu.History)?.opening,
                rememberComposeApplicationMenuRenderer(),
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
            ApplicationPopupContent(
                viewModel, frame.popup, rememberComposeApplicationPopupRenderer(openExternalUrl),
                onOperationFailure, openExternalUrl,
            )
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
    LaunchedEffect(enabled) { if (!enabled) preview.value = null }
    DisposableEffect(preview) { onDispose { preview.value = null } }
    Box(
        Modifier.width(GraphicalSidebarSplitterWidth).fillMaxHeight()
            .semantics { contentDescription = "Resize ${side.name.lowercase()} sidebar" }
            .pointerInput(side, enabled) {
                if (enabled) detectHorizontalDragGestures(
                    onDragStart = { preview.value = width },
                    onDragCancel = { preview.value = null },
                    onDragEnd = {
                        val pixels = (preview.value ?: width).coerceIn(0f, maximum)
                        preview.value = null
                        commit((pixels / unit).roundToInt().coerceAtLeast(MinimumSidebarWidthColumns))
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
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

private class GraphicalRootOwnerKey(private val owner: Any?) {
    override fun equals(other: Any?): Boolean = other is GraphicalRootOwnerKey && other.owner === owner
    override fun hashCode(): Int = 0
}

private val GraphicalSidebarSplitterWidth = 8.dp
internal val LocalGraphicalHostCapabilities = staticCompositionLocalOf { SettingsHostCapabilities() }
