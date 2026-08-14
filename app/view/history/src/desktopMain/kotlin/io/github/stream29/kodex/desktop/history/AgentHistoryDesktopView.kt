package io.github.stream29.kodex.desktop.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.InvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentDeliveryResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageGenerationResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageGenerationToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageViewResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageViewToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableInterruptAgentResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableJsonToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableListAgentsResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableMcpToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableMultiAgentOperation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableMultiAgentToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSpawnAgentResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableToolSearchEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWaitAgentResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingImageGenerationToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingImageViewToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMcpToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMultiAgentInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMultiAgentToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolSearchEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingWebSearchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentHistoryTarget
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.agent.contract.AgentStreamKind
import io.github.stream29.kodex.app.agent.contract.AgentStreamState
import io.github.stream29.kodex.app.agent.contract.AgentStreamTail
import io.github.stream29.kodex.app.history.contract.AgentHistoryEdgeState
import io.github.stream29.kodex.app.history.contract.AgentHistoryEntry
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadRequest
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.AgentHistoryWindowStatus
import io.github.stream29.kodex.desktop.components.desktopSecondaryClick
import io.github.stream29.kodex.desktop.patch.PendingPatchDesktopView
import io.github.stream29.kodex.desktop.patch.StablePatchDesktopView
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.CallToolResult
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.FunctionCallOutputBody
import io.github.stream29.kodex.openai.FunctionCallOutputContentItem
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.LoadableToolSpec
import io.github.stream29.kodex.openai.MessagePhase
import io.github.stream29.kodex.openai.ReasoningItemReasoningSummary
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiNamespace
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.WebSearchAction
import io.github.stream29.kodex.tool.imagegeneration.ImageGenToolArguments
import io.github.stream29.kodex.tool.multiagent.FollowupTaskArgs
import io.github.stream29.kodex.tool.multiagent.InterruptAgentArgs
import io.github.stream29.kodex.tool.multiagent.InterruptAgentResult
import io.github.stream29.kodex.tool.multiagent.ListAgentsArgs
import io.github.stream29.kodex.tool.multiagent.ListAgentsResult
import io.github.stream29.kodex.tool.multiagent.MultiAgentStatus
import io.github.stream29.kodex.tool.multiagent.SendMessageArgs
import io.github.stream29.kodex.tool.multiagent.SpawnAgentArgs
import io.github.stream29.kodex.tool.multiagent.SpawnAgentResult
import io.github.stream29.kodex.tool.multiagent.SpawnForkMode
import io.github.stream29.kodex.tool.multiagent.WaitAgentArgs
import io.github.stream29.kodex.tool.multiagent.WaitAgentResult
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.toolsearch.SearchToolCallParams
import io.github.stream29.kodex.tool.toolsearch.ToolSearchResult
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecOutput
import io.github.stream29.kodex.tool.unifiedexec.WriteStdinArguments
import io.github.stream29.kodex.tool.viewimage.ViewImageToolArguments
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Desktop history surface. The ViewModel remains the only history-window owner;
 * scrolling and row expansion are renderer-local.
 */
@Composable
public fun AgentHistoryDesktopView(
    agentId: String,
    viewModel: AgentHistoryViewModel,
    streamState: AgentStreamState,
    uiState: AgentHistoryDesktopUiState,
    shellSessions: AgentShellSessionRegistry,
    canActOnHistory: Boolean,
    onRequestRevert: (AgentHistoryTarget) -> Unit,
    onRequestFork: (AgentHistoryTarget) -> Unit,
    modifier: Modifier = Modifier,
): Unit {
    val window by viewModel.window.collectAsState()
    val windowSnapshot = window
    val listState = uiState.listState
    val scope = rememberCoroutineScope()
    val entryFocusRequesters = remember(agentId) {
        mutableMapOf<StoredDesktopHistoryKey, FocusRequester>()
    }
    val committedItems = windowSnapshot.entries.map { entry ->
        StoredDesktopHistoryItem(
            key = StoredDesktopHistoryKey(
                agentId = agentId,
                generation = windowSnapshot.generation,
                storageIndex = entry.key.primaryStorageIndex,
                providerId = entry.key.providerId,
            ),
            entry = entry,
        )
    }
    val entryKeys = committedItems.map { item -> item.key }

    LaunchedEffect(
        listState,
        uiState,
        windowSnapshot.generation,
        windowSnapshot.entries.size,
        streamState.pendingEvents.size,
        streamState.tail,
    ) {
        if (uiState.followsLatest) {
            yield()
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            if (lastIndex >= 0) listState.scrollToItem(lastIndex)
        }
    }
    LaunchedEffect(listState, uiState) {
        snapshotFlow { listState.isAtLatestHistoryEdge() }
            .distinctUntilChanged()
            .collect { atLatest ->
                if (uiState.followsLatest && !atLatest) {
                    val lastIndex = listState.layoutInfo.totalItemsCount - 1
                    if (lastIndex >= 0) listState.scrollToItem(lastIndex)
                } else {
                    uiState.reconcileLatest(atLatest)
                }
            }
    }

    LaunchedEffect(
        viewModel,
        listState,
        windowSnapshot.generation,
        streamState.pendingEvents.size,
        windowSnapshot.entries.size,
        windowSnapshot.olderEdge,
    ) {
        val initialCursor = windowSnapshot.olderEdge.loadableCursor()
        if (windowSnapshot.entries.isEmpty() || initialCursor == null) return@LaunchedEffect

        val prefetchKeys = entryKeys.takeLast(HistoryPrefetchDistance + 1).toSet()
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.any { item ->
                item.key in prefetchKeys
            }
        }
            .distinctUntilChanged()
            .filter { nearOlderBoundary -> nearOlderBoundary }
            .collect {
                val cursor = windowSnapshot.olderEdge.loadableCursor() ?: return@collect
                viewModel.request(
                    AgentHistoryLoadRequest(cursor, HistoryLoadBudget),
                )
            }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .historyPointerScroll {
                uiState.beginUserScroll()
                scope.launch {
                    yield()
                    uiState.recordUserScroll(listState.isAtLatestHistoryEdge())
                }
            }
            .onPreviewKeyEvent { event ->
                val towardOlder = when {
                    event.type != KeyEventType.KeyDown ||
                        event.isAltPressed ||
                        event.isCtrlPressed ||
                        event.isMetaPressed ||
                        event.isShiftPressed -> return@onPreviewKeyEvent false

                    event.key == Key.PageUp -> true
                    event.key == Key.PageDown -> false
                    else -> return@onPreviewKeyEvent false
                }
                scope.launch {
                    listState.pageHistory(
                        towardOlder = towardOlder,
                        uiState = uiState,
                        entryFocusRequesters = entryFocusRequesters,
                    )
                }
                true
            },
        state = listState,
        reverseLayout = false,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 8.dp,
            vertical = 4.dp,
        ),
    ) {
        val failureMessage = when (val status = windowSnapshot.status) {
            is AgentHistoryWindowStatus.Failed -> status.message
            else -> (windowSnapshot.olderEdge as? AgentHistoryEdgeState.Failed)?.message
        }
        failureMessage?.let { message ->
            item(key = "failed-${windowSnapshot.generation}") {
                HistoryStatus("History error: $message")
            }
        }
        if (
            windowSnapshot.status is AgentHistoryWindowStatus.Initializing ||
            windowSnapshot.olderEdge is AgentHistoryEdgeState.Loading
        ) {
            item(key = "loading-${windowSnapshot.generation}") {
                HistoryStatus("Loading history…")
            }
        }

        items(
            items = committedItems.asReversed(),
            key = { item -> item.key },
            contentType = { item -> item.entry.event::class.simpleName },
        ) { item ->
            CommittedHistoryDesktopItem(
                item = item,
                entryFocusRequesters = entryFocusRequesters,
                uiState = uiState,
                shellSessions = shellSessions,
                canActOnHistory = canActOnHistory,
                onRequestRevert = onRequestRevert,
                onRequestFork = onRequestFork,
            )
        }

        itemsIndexed(
            items = streamState.pendingEvents,
            key = { index, event ->
                PendingDesktopHistoryKey(
                    agentId = agentId,
                    generation = windowSnapshot.generation,
                    identity = event.pendingIdentity(index),
                )
            },
            contentType = { _, _ -> "pending" },
        ) { index, event ->
            PendingEventRow(
                event = event,
                shellSessions = shellSessions,
                uiState = uiState,
                expansionPrefix = PendingDesktopHistoryKey(
                    agentId = agentId,
                    generation = windowSnapshot.generation,
                    identity = event.pendingIdentity(index),
                ),
            )
        }

        streamState.tail?.let { tail ->
            item(
                key = StreamingDesktopHistoryKey(
                    agentId = agentId,
                    identity = tail.desktopHistoryIdentity(),
                ),
            ) {
                StreamingTailRow(
                    tail = tail,
                    uiState = uiState,
                    expansionKey = DesktopExpansionKey(
                        StreamingDesktopHistoryKey(
                            agentId = agentId,
                            identity = tail.desktopHistoryIdentity(),
                        ),
                        "streaming",
                    ),
                ) {
                    scope.launch {
                        if (uiState.followsLatest) {
                            yield()
                            val lastIndex = listState.layoutInfo.totalItemsCount - 1
                            if (lastIndex >= 0) listState.scrollToItem(lastIndex)
                        }
                    }
                }
            }
        }

        if (
            streamState.tail == null &&
            windowSnapshot.entries.isEmpty() &&
            streamState.pendingEvents.isEmpty() &&
            windowSnapshot.status !is AgentHistoryWindowStatus.Initializing &&
            windowSnapshot.olderEdge !is AgentHistoryEdgeState.Loading &&
            failureMessage == null
        ) {
            item(key = "empty-${windowSnapshot.generation}") {
                HistoryStatus("No committed conversation items")
            }
        }
    }
}

@Composable
internal fun CommittedHistoryDesktopItem(
    item: StoredDesktopHistoryItem,
    entryFocusRequesters: MutableMap<StoredDesktopHistoryKey, FocusRequester>,
    uiState: AgentHistoryDesktopUiState,
    shellSessions: AgentShellSessionRegistry,
    canActOnHistory: Boolean,
    onRequestRevert: (AgentHistoryTarget) -> Unit,
    onRequestFork: (AgentHistoryTarget) -> Unit,
    modifier: Modifier = Modifier,
): Unit {
    val focusRequester = remember(item.key) { FocusRequester() }
    DisposableEffect(item.key, focusRequester) {
        entryFocusRequesters[item.key] = focusRequester
        onDispose {
            if (entryFocusRequesters[item.key] === focusRequester) {
                entryFocusRequesters.remove(item.key)
            }
        }
    }
    CommittedHistoryDesktopRow(
        entry = item.entry,
        generation = item.key.generation,
        focusRequester = focusRequester,
        uiState = uiState,
        shellSessions = shellSessions,
        canActOnHistory = canActOnHistory,
        onRequestRevert = onRequestRevert,
        onRequestFork = onRequestFork,
        modifier = modifier,
    )
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun CommittedHistoryDesktopRow(
    entry: AgentHistoryEntry,
    generation: Long,
    focusRequester: FocusRequester,
    uiState: AgentHistoryDesktopUiState,
    shellSessions: AgentShellSessionRegistry,
    canActOnHistory: Boolean,
    onRequestRevert: (AgentHistoryTarget) -> Unit,
    onRequestFork: (AgentHistoryTarget) -> Unit,
    modifier: Modifier = Modifier,
): Unit {
    val event = entry.event
    val target = AgentHistoryTarget(
        generation = generation,
        storageIndex = entry.key.primaryStorageIndex,
    )
    var focused by remember(target) { mutableStateOf(false) }
    var menuExpanded by remember(target) { mutableStateOf(false) }
    val focusIndicator = MaterialTheme.colorScheme.primary
    Box {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .drawBehind {
                    if (focused) {
                        drawRect(
                            color = focusIndicator,
                            size = Size(2.dp.toPx(), size.height),
                        )
                    }
                }
                .focusable()
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (event.buttons.isPrimaryPressed) focusRequester.requestFocus()
                }
                .then(
                    if (canActOnHistory) {
                        Modifier.desktopSecondaryClick(focusable = false) {
                            focusRequester.requestFocus()
                            menuExpanded = true
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            event.roleLabel()?.let { label ->
                DesktopHistoryText(
                    value = label,
                    color = if (event is StableCleanEvent.DeveloperMessage) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
            StableEventDesktopView(
                event = event,
                shellSessions = shellSessions,
                uiState = uiState,
                expansionPrefix = target,
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Revert to here") },
                onClick = {
                    menuExpanded = false
                    onRequestRevert(target)
                },
            )
            DropdownMenuItem(
                text = { Text("Fork from here") },
                onClick = {
                    menuExpanded = false
                    onRequestFork(target)
                },
            )
        }
    }
}

@Composable
internal fun StableEventDesktopView(
    event: StableCleanEvent,
    shellSessions: AgentShellSessionRegistry,
    uiState: AgentHistoryDesktopUiState,
    expansionPrefix: Any,
): Unit {
    when (event) {
        is StableCleanEvent.UserMessage -> MessageContent(event.content)
        is StableCleanEvent.AssistantMessage -> MessageContent(event.content)
        is StableCleanEvent.DeveloperMessage -> MessageContent(
            content = event.content,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is StableCleanEvent.AgentMessage -> Column {
            event.content.forEach { part ->
                DesktopHistoryText(
                    value = when (part) {
                        is AgentMessageInputContent.InputText -> part.text
                        is AgentMessageInputContent.EncryptedContent -> "[encrypted content]"
                    },
                )
            }
        }

        is StableCleanEvent.Reasoning -> ReasoningView(
            event = event,
            uiState = uiState,
            expansionKey = DesktopExpansionKey(expansionPrefix, "reasoning"),
        )

        StableCleanEvent.ContextCompaction -> DesktopHistoryText(
            value = "Context compacted",
            dim = true,
        )

        is StablePatchToolEvent -> StablePatchDesktopView(event)
        is StablePlanUpdate -> PlanView(event)
        is StableCommandExecutionToolEvent -> CommandView(
            event = event,
            shellSessions = shellSessions,
            uiState = uiState,
            expansionPrefix = expansionPrefix,
        )

        is StableJsonToolEvent -> DesktopToolEvent(
            summary = functionToolSummary(event.name, event.namespace),
            rawName = qualifiedName(event.name, event.namespace),
            status = event.success.completedStatus(),
            uiState = uiState,
            expansionKey = DesktopExpansionKey(expansionPrefix, event.callId),
            content = {
                section("Arguments") { DesktopJsonDetail("Arguments", event.arguments) }
                section("Result") { DesktopJsonDetail("Result", event.result) }
            },
        )

        is StableTextToolEvent -> DesktopToolEvent(
            summary = functionToolSummary(event.name, event.namespace),
            rawName = qualifiedName(event.name, event.namespace),
            status = event.success.completedStatus(),
            uiState = uiState,
            expansionKey = DesktopExpansionKey(expansionPrefix, event.callId),
            content = {
                section("Arguments") { DesktopJsonDetail("Arguments", event.arguments) }
                section("Result") { DesktopDetail("Result", event.result) }
            },
        )

        is StableCustomToolEvent -> DesktopToolEvent(
            summary = functionToolSummary(event.name, event.namespace),
            rawName = qualifiedName(event.name, event.namespace),
            status = event.success.completedStatus(),
            uiState = uiState,
            expansionKey = DesktopExpansionKey(expansionPrefix, event.callId),
            content = {
                section("Input") { DesktopDetail("Input", event.input) }
                section("Result") { event.result.renderDesktopDetails() }
            },
        )

        is StableImageGenerationToolEvent -> event.renderImageGenerationDesktop(
            uiState,
            DesktopExpansionKey(expansionPrefix, event.callId),
        )

        is StableImageViewToolEvent -> event.renderImageViewDesktop(
            uiState,
            DesktopExpansionKey(expansionPrefix, event.callId),
        )

        is StableMcpToolEvent -> DesktopToolEvent(
            summary = qualifiedName(event.name, event.namespace),
            rawName = qualifiedName(event.name, event.namespace),
            status = event.result.completedStatus(),
            uiState = uiState,
            expansionKey = DesktopExpansionKey(expansionPrefix, event.callId),
            content = {
                section("Arguments") { DesktopJsonDetail("Arguments", event.arguments) }
                section("Result") { event.result.renderDesktopDetails() }
            },
        )

        is StableMultiAgentToolEvent -> event.renderMultiAgentDesktop(
            uiState,
            DesktopExpansionKey(expansionPrefix, event.callId),
        )

        is StableRequestUserInputToolEvent -> event.renderRequestUserInputDesktop(
            uiState,
            DesktopExpansionKey(expansionPrefix, event.callId),
        )

        is StableToolSearchEvent -> event.renderToolSearchDesktop(
            uiState,
            DesktopExpansionKey(expansionPrefix, event.callId),
        )

        is StableWebSearchToolEvent -> event.renderWebSearchDesktop(
            uiState,
            DesktopExpansionKey(expansionPrefix, event.callId),
        )

        is StableCleanEvent.InvalidToolCall -> DesktopToolEvent(
            summary = "Unable to call a tool",
            rawName = event.invocation.displayName(),
            status = "failed",
            uiState = uiState,
            expansionKey = DesktopExpansionKey(expansionPrefix, event.callId),
        ) {
            section("Invocation") { event.invocation.renderDesktopDetails() }
            section("Error") { DesktopDetail("Error", event.message) }
        }

        is StableCleanEvent.ServerToolSearch -> DesktopToolEvent(
            summary = "Load tools from the server",
            rawName = "server_tool_search",
            status = event.output.status,
            uiState = uiState,
            expansionKey = DesktopExpansionKey(
                expansionPrefix,
                event.call.id?.value ?: "server_tool_search",
            ),
        ) {
            section("Arguments") { DesktopJsonDetail("Arguments", event.call.arguments) }
            section("Tools") { DesktopLoadableTools(event.output.tools) }
        }

        is StableCleanEvent.WebSearchCall -> DesktopToolEvent(
            summary = event.item.action?.toolSummary() ?: "Search the web",
            rawName = "hosted_web_search",
            status = event.item.status ?: "completed",
            uiState = uiState,
            expansionKey = DesktopExpansionKey(
                expansionPrefix,
                event.item.id?.value ?: "hosted_web_search",
            ),
        ) {
            section("Arguments") {
                event.item.action?.renderDesktopDetails()
                    ?: DesktopDetail("Request", "hosted web search")
            }
        }

        is StableCleanEvent.ImageGenerationCall -> DesktopToolEvent(
            summary = event.item.revisedPrompt?.imageGenerationSummary()
                ?: "Generate an image",
            rawName = "hosted_image_generation",
            status = event.item.status,
            uiState = uiState,
            expansionKey = DesktopExpansionKey(
                expansionPrefix,
                event.item.id?.value ?: "hosted_image_generation",
            ),
        ) {
            section("Result") {
                event.item.revisedPrompt
                    ?.takeIf(String::isNotBlank)
                    ?.let { DesktopDetail("Revised prompt", it) }
                DesktopDetail("Image", "[generated image]")
            }
        }
    }
}

@Composable
private fun ReasoningView(
    event: StableCleanEvent.Reasoning,
    uiState: AgentHistoryDesktopUiState,
    expansionKey: Any,
): Unit {
    DesktopExpandableHistoryEvent(
        header = "Thinking",
        uiState = uiState,
        expansionKey = expansionKey,
        dim = true,
        italic = true,
    ) {
        if (event.display.isNotBlank()) {
            DesktopHistoryText(
                value = event.display,
                dim = true,
                italic = true,
            )
        }
    }
}

@Composable
private fun MessageContent(
    content: List<ContentItem>,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
): Unit {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        content.forEach { part ->
            when (part) {
                is ContentItem.InputText -> DesktopHistoryText(part.text, color = color)
                is ContentItem.OutputText -> DesktopHistoryText(part.text, color = color)
                is ContentItem.InputImage -> DesktopDetail(
                    label = "Image",
                    value = "${part.imageUrl.safeMediaReference()} · " +
                        (part.detail?.name?.lowercase() ?: "auto"),
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun PlanView(event: StablePlanUpdate): Unit {
    PlanView(
        title = "Updated Plan",
        explanation = event.arguments.explanation,
        plan = event.arguments.plan,
        pending = false,
    )
}

@Composable
private fun PlanView(
    title: String,
    explanation: String?,
    plan: List<io.github.stream29.kodex.openai.PlanItemArg>,
    pending: Boolean,
): Unit {
    val note = explanation?.trim()?.takeIf(String::isNotEmpty)
    Column(Modifier.fillMaxWidth()) {
        DesktopHistoryText(
            value = "• $title",
            fontWeight = FontWeight.Bold,
            dim = pending,
        )
        note?.let { DesktopHistoryText("  └ $it", dim = true) }
        if (plan.isEmpty()) {
            val indent = if (note == null) "  └ " else "    "
            DesktopHistoryText("${indent}(no steps provided)", dim = true)
        } else {
            plan.forEachIndexed { index, item ->
                val indent = if (note == null && index == 0) "  └ " else "    "
                DesktopHistoryText(
                    value = "$indent${item.status.desktopPlanMarker()} ${item.step}",
                    dim = pending,
                )
            }
        }
    }
}

@Composable
private fun CommandView(
    event: StableCommandExecutionToolEvent,
    shellSessions: AgentShellSessionRegistry,
    uiState: AgentHistoryDesktopUiState,
    expansionPrefix: Any,
): Unit {
    val active by shellSessions.activeSessions.collectAsState()
    val session = active[event.activeSessionId()]
    val completed = session.completedForDesktopPresentation()
    DesktopToolEvent(
        summary = event.action.toolSummary(session?.arguments),
        rawName = event.action.toolName(),
        status = event.result.status(session, completed),
        uiState = uiState,
        expansionKey = DesktopExpansionKey(expansionPrefix, event.callId),
    ) {
        section("Arguments") {
            event.action.renderDesktopDetails(sourceArguments = session?.arguments)
        }
        session?.let {
            section("Process") {
                DesktopDetail(
                    "Process",
                    if (completed) "finished; final output is ready" else "running",
                )
            }
        }
        section("Result") { event.result.renderDesktopDetails() }
    }
}

@Composable
private fun PendingEventRow(
    event: UnstableCleanEvent,
    shellSessions: AgentShellSessionRegistry,
    uiState: AgentHistoryDesktopUiState,
    expansionPrefix: Any,
): Unit {
    when (event) {
        is PendingFunctionToolEvent -> DesktopToolEvent(
            summary = functionToolSummary(event.name, event.namespace),
            rawName = qualifiedName(event.name, event.namespace),
            status = "running",
            uiState = uiState,
            expansionKey = DesktopExpansionKey(expansionPrefix, event.callId),
            dimDetails = true,
        ) {
            section("Arguments") { DesktopJsonDetail("Arguments", event.arguments, dim = true) }
        }

        is PendingCustomToolEvent -> DesktopToolEvent(
            summary = functionToolSummary(event.name, event.namespace),
            rawName = qualifiedName(event.name, event.namespace),
            status = "running",
            uiState = uiState,
            expansionKey = DesktopExpansionKey(expansionPrefix, event.callId),
            dimDetails = true,
        ) {
            section("Input") { DesktopDetail("Input", event.input, dim = true) }
        }

        is PendingPatchToolEvent -> PendingPatchDesktopView(event)

        is PendingPlanUpdate -> PlanView(
            title = "Updating Plan",
            explanation = event.arguments.explanation,
            plan = event.arguments.plan,
            pending = true,
        )

        is PendingCommandExecutionToolEvent -> PendingCommandView(
            event = event,
            shellSessions = shellSessions,
            uiState = uiState,
            expansionPrefix = expansionPrefix,
        )

        is PendingMultiAgentToolEvent -> DesktopToolEvent(
            summary = event.operation.toolSummary(),
            rawName = event.operation.toolName,
            status = "running",
            uiState = uiState,
            expansionKey = DesktopExpansionKey(expansionPrefix, event.callId),
            dimDetails = true,
        ) {
            section("Arguments") { event.operation.renderDesktopDetails(dim = true) }
        }

        is PendingImageGenerationToolEvent -> DesktopToolEvent(
            summary = event.arguments.toolSummary(),
            rawName = "image_gen.imagegen",
            status = "running",
            uiState = uiState,
            expansionKey = DesktopExpansionKey(expansionPrefix, event.callId),
            dimDetails = true,
        ) {
            section("Arguments") { event.arguments.renderDesktopDetails(dim = true) }
        }

        is PendingImageViewToolEvent -> DesktopToolEvent(
            summary = event.arguments.toolSummary(),
            rawName = "view_image",
            status = "running",
            uiState = uiState,
            expansionKey = DesktopExpansionKey(expansionPrefix, event.callId),
            dimDetails = true,
        ) {
            section("Arguments") { event.arguments.renderDesktopDetails(dim = true) }
        }

        is PendingMcpToolEvent -> DesktopToolEvent(
            summary = qualifiedName(event.name, event.namespace),
            rawName = qualifiedName(event.name, event.namespace),
            status = "running",
            uiState = uiState,
            expansionKey = DesktopExpansionKey(expansionPrefix, event.callId),
            dimDetails = true,
        ) {
            section("Arguments") { DesktopJsonDetail("Arguments", event.arguments, dim = true) }
        }

        is PendingRequestUserInputToolEvent -> DesktopToolEvent(
            summary = event.arguments.toolSummary(),
            rawName = "request_user_input",
            status = "running",
            uiState = uiState,
            expansionKey = DesktopExpansionKey(expansionPrefix, event.callId),
            dimDetails = true,
        ) {
            section("Arguments") { event.arguments.renderDesktopDetails(dim = true) }
        }

        is PendingToolSearchEvent -> DesktopToolEvent(
            summary = event.arguments.toolSummary(),
            rawName = "tool_search",
            status = "running",
            uiState = uiState,
            expansionKey = DesktopExpansionKey(expansionPrefix, event.callId),
            dimDetails = true,
        ) {
            section("Arguments") { event.arguments.renderDesktopDetails(dim = true) }
        }

        is PendingWebSearchToolEvent -> DesktopToolEvent(
            summary = event.commands.toolSummary(),
            rawName = "web.run",
            status = "running",
            uiState = uiState,
            expansionKey = DesktopExpansionKey(expansionPrefix, event.callId),
            dimDetails = true,
        ) {
            section("Arguments") { event.commands.renderDesktopDetails(dim = true) }
        }

        is PendingInvalidToolCall -> DesktopToolEvent(
            summary = "Unable to call a tool",
            rawName = event.invocation.displayName(),
            status = "failed",
            uiState = uiState,
            expansionKey = DesktopExpansionKey(expansionPrefix, event.callId),
            dimDetails = true,
        ) {
            section("Invocation") { event.invocation.renderDesktopDetails(dim = true) }
            section("Error") { DesktopDetail("Error", event.message, dim = true) }
        }

        is PendingServerToolSearch -> DesktopToolEvent(
            summary = "Load tools from the server",
            rawName = "server_tool_search",
            status = event.call.status ?: "running",
            uiState = uiState,
            expansionKey = DesktopExpansionKey(
                expansionPrefix,
                event.call.id?.value ?: "server_tool_search",
            ),
            dimDetails = true,
        ) {
            section("Arguments") {
                DesktopJsonDetail("Arguments", event.call.arguments, dim = true)
            }
        }
    }
}

@Composable
private fun PendingCommandView(
    event: PendingCommandExecutionToolEvent,
    shellSessions: AgentShellSessionRegistry,
    uiState: AgentHistoryDesktopUiState,
    expansionPrefix: Any,
): Unit {
    val active by shellSessions.activeSessions.collectAsState()
    val session = active[event.action.activeSessionId()]
    val completed = session.completedForDesktopPresentation()
    DesktopToolEvent(
        summary = event.action.toolSummary(session?.arguments),
        rawName = event.toolName,
        status = "running",
        uiState = uiState,
        expansionKey = DesktopExpansionKey(expansionPrefix, event.callId),
        dimDetails = true,
    ) {
        section("Arguments") {
            event.action.renderDesktopDetails(
                sourceArguments = session?.arguments,
                dim = true,
            )
        }
        session?.let {
            section("Process") {
                DesktopDetail(
                    label = "Process",
                    value = if (completed) {
                        "finished; final output is ready"
                    } else {
                        "running"
                    },
                    dim = true,
                )
            }
        }
    }
}

@Composable
private fun DesktopToolEvent(
    summary: String,
    rawName: String?,
    status: String,
    uiState: AgentHistoryDesktopUiState,
    expansionKey: Any,
    dimDetails: Boolean = false,
    content: @Composable DesktopToolDetailsScope.() -> Unit,
): Unit {
    val expanded = uiState.isExpanded(expansionKey)
    Column(Modifier.fillMaxWidth()) {
        DesktopExpandableHeader(
            text = "${if (expanded) "v" else ">"} $summary",
            color = desktopToolHeaderColor(status),
            onClick = { uiState.setExpanded(expansionKey, !expanded) },
        )
        if (expanded) {
            rawName
                ?.takeIf(String::isNotBlank)
                ?.let { DesktopDetail("Tool", it, dim = dimDetails) }
            DesktopToolDetailsScope(
                uiState = uiState,
                expansionPrefix = expansionKey,
                dim = dimDetails,
            ).content()
        }
    }
}

private class DesktopToolDetailsScope(
    private val uiState: AgentHistoryDesktopUiState,
    private val expansionPrefix: Any,
    private val dim: Boolean,
) {
    @Composable
    fun section(
        label: String,
        content: @Composable () -> Unit,
    ): Unit {
        val expansionKey = DesktopExpansionKey(expansionPrefix, label)
        val expanded = uiState.isExpanded(expansionKey)
        DesktopExpandableHeader(
            text = "${if (expanded) "v" else ">"} $label",
            color = if (dim) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            onClick = { uiState.setExpanded(expansionKey, !expanded) },
        )
        if (expanded) content()
    }
}

@Composable
private fun DesktopExpandableHistoryEvent(
    header: String,
    uiState: AgentHistoryDesktopUiState,
    expansionKey: Any,
    dim: Boolean = false,
    italic: Boolean = false,
    content: @Composable () -> Unit,
): Unit {
    val expanded = uiState.isExpanded(expansionKey)
    Column(Modifier.fillMaxWidth()) {
        DesktopExpandableHeader(
            text = "${if (expanded) "v" else ">"} $header",
            color = if (dim) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            italic = italic,
            onClick = { uiState.setExpanded(expansionKey, !expanded) },
        )
        if (expanded) content()
    }
}

@Composable
private fun DesktopExpandableHeader(
    text: String,
    color: Color,
    italic: Boolean = false,
    onClick: () -> Unit,
): Unit {
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DesktopHistoryText(
    value: String,
    color: Color = Color.Unspecified,
    dim: Boolean = false,
    italic: Boolean = false,
    fontWeight: FontWeight = FontWeight.Normal,
): Unit {
    if (value.isBlank()) return
    Text(
        text = value,
        color = when {
            color != Color.Unspecified -> color
            dim -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        },
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        fontWeight = fontWeight,
    )
}

@Composable
private fun DesktopDetail(
    label: String,
    value: String,
    dim: Boolean = false,
    color: Color = Color.Unspecified,
): Unit {
    if (value.isNotBlank()) {
        DesktopHistoryText(
            value = "$label: $value",
            color = color,
            dim = dim,
        )
    }
}

@Composable
private fun DesktopJsonDetail(
    label: String,
    value: JsonElement,
    dim: Boolean = false,
): Unit {
    DesktopDetail(label, value.historyPreview(), dim = dim)
}

@Composable
private fun DesktopLoadableTools(
    tools: List<LoadableToolSpec>,
    dim: Boolean = false,
): Unit {
    if (tools.isEmpty()) {
        DesktopDetail("Tools", "none", dim = dim)
    } else {
        tools.forEach { tool -> DesktopDetail("Tools", tool.displayName(), dim = dim) }
    }
}

@Composable
private fun StableImageGenerationToolEvent.renderImageGenerationDesktop(
    uiState: AgentHistoryDesktopUiState,
    expansionKey: Any,
): Unit {
    DesktopToolEvent(
        summary = arguments.toolSummary(),
        rawName = "image_gen.imagegen",
        status = result.status(),
        uiState = uiState,
        expansionKey = expansionKey,
    ) {
        section("Arguments") { arguments.renderDesktopDetails() }
        section("Result") {
            when (val value = result) {
                is StableImageGenerationResult.Success -> {
                    DesktopDetail("Image", "[generated inline image]")
                    value.output.outputHint
                        ?.takeIf(String::isNotBlank)
                        ?.let { DesktopDetail("Hint", it) }
                    value.savedPath
                        ?.takeIf(String::isNotBlank)
                        ?.let { DesktopDetail("Saved", it) }
                }

                is StableImageGenerationResult.Failure ->
                    DesktopDetail("Error", value.message)
            }
        }
    }
}

@Composable
private fun StableImageViewToolEvent.renderImageViewDesktop(
    uiState: AgentHistoryDesktopUiState,
    expansionKey: Any,
): Unit {
    DesktopToolEvent(
        summary = arguments.toolSummary(),
        rawName = "view_image",
        status = result.status(),
        uiState = uiState,
        expansionKey = expansionKey,
    ) {
        section("Arguments") { arguments.renderDesktopDetails() }
        section("Result") {
            when (val value = result) {
                is StableImageViewResult.Success -> {
                    DesktopDetail("Image", value.output.imageUrl.safeMediaReference())
                    DesktopDetail("Output detail", value.output.detail.name.lowercase())
                }

                is StableImageViewResult.Failure -> DesktopDetail("Error", value.message)
            }
        }
    }
}

@Composable
private fun StableMultiAgentToolEvent.renderMultiAgentDesktop(
    uiState: AgentHistoryDesktopUiState,
    expansionKey: Any,
): Unit {
    when (val value = operation) {
        is StableMultiAgentOperation.SpawnAgent -> DesktopToolEvent(
            summary = value.arguments.toolSummary(),
            rawName = "spawn_agent",
            status = value.result.completedStatus(),
            uiState = uiState,
            expansionKey = expansionKey,
        ) {
            section("Arguments") { value.arguments.renderDesktopDetails() }
            section("Result") { value.result.renderDesktopDetails() }
        }

        is StableMultiAgentOperation.SendMessage -> DesktopToolEvent(
            summary = value.arguments.toolSummary(),
            rawName = "send_message",
            status = value.result.completedStatus(),
            uiState = uiState,
            expansionKey = expansionKey,
        ) {
            section("Arguments") { value.arguments.renderDesktopDetails() }
            section("Result") { value.result.renderDesktopDetails() }
        }

        is StableMultiAgentOperation.FollowupTask -> DesktopToolEvent(
            summary = value.arguments.toolSummary(),
            rawName = "followup_task",
            status = value.result.completedStatus(),
            uiState = uiState,
            expansionKey = expansionKey,
        ) {
            section("Arguments") { value.arguments.renderDesktopDetails() }
            section("Result") { value.result.renderDesktopDetails() }
        }

        is StableMultiAgentOperation.WaitAgent -> DesktopToolEvent(
            summary = value.arguments.toolSummary(),
            rawName = "wait_agent",
            status = value.result.completedStatus(),
            uiState = uiState,
            expansionKey = expansionKey,
        ) {
            section("Arguments") { value.arguments.renderDesktopDetails() }
            section("Result") { value.result.renderDesktopDetails() }
        }

        is StableMultiAgentOperation.InterruptAgent -> DesktopToolEvent(
            summary = value.arguments.toolSummary(),
            rawName = "interrupt_agent",
            status = value.result.completedStatus(),
            uiState = uiState,
            expansionKey = expansionKey,
        ) {
            section("Arguments") { value.arguments.renderDesktopDetails() }
            section("Result") { value.result.renderDesktopDetails() }
        }

        is StableMultiAgentOperation.ListAgents -> DesktopToolEvent(
            summary = value.arguments.toolSummary(),
            rawName = "list_agents",
            status = value.result.completedStatus(),
            uiState = uiState,
            expansionKey = expansionKey,
        ) {
            section("Arguments") { value.arguments.renderDesktopDetails() }
            section("Result") { value.result.renderDesktopDetails() }
        }
    }
}

@Composable
private fun StableRequestUserInputToolEvent.renderRequestUserInputDesktop(
    uiState: AgentHistoryDesktopUiState,
    expansionKey: Any,
): Unit {
    DesktopToolEvent(
        summary = arguments.toolSummary(),
        rawName = "request_user_input",
        status = result.status(),
        uiState = uiState,
        expansionKey = expansionKey,
    ) {
        section("Arguments") { arguments.renderDesktopDetails() }
        section("Result") {
            when (val value = result) {
                is StableRequestUserInputResult.Answered ->
                    value.response.renderDesktopDetails(arguments)

                is StableRequestUserInputResult.Failure ->
                    DesktopDetail("Error", value.message)
            }
        }
    }
}

@Composable
private fun StableToolSearchEvent.renderToolSearchDesktop(
    uiState: AgentHistoryDesktopUiState,
    expansionKey: Any,
): Unit {
    DesktopToolEvent(
        summary = arguments.toolSummary(),
        rawName = "tool_search",
        status = result.status(),
        uiState = uiState,
        expansionKey = expansionKey,
    ) {
        section("Arguments") { arguments.renderDesktopDetails() }
        section("Result") {
            when (val value = result) {
                is ToolSearchResult.Success -> DesktopLoadableTools(value.tools)
                is ToolSearchResult.InvalidArguments -> DesktopDetail("Error", value.message)
            }
        }
    }
}

@Composable
private fun StableWebSearchToolEvent.renderWebSearchDesktop(
    uiState: AgentHistoryDesktopUiState,
    expansionKey: Any,
): Unit {
    DesktopToolEvent(
        summary = commands.toolSummary(),
        rawName = "web.run",
        status = result.status(),
        uiState = uiState,
        expansionKey = expansionKey,
    ) {
        section("Arguments") { commands.renderDesktopDetails() }
        section("Result") {
            when (val value = result) {
                is StableWebSearchResult.Success ->
                    DesktopDetail("Output", value.response.output)

                is StableWebSearchResult.Failure -> DesktopDetail("Error", value.message)
            }
        }
    }
}

@Composable
private fun InvalidToolInvocation.renderDesktopDetails(dim: Boolean = false): Unit {
    when (this) {
        is InvalidToolInvocation.Function -> DesktopDetail("Arguments", arguments, dim = dim)
        is InvalidToolInvocation.Custom -> DesktopDetail("Input", input, dim = dim)
        is InvalidToolInvocation.ToolSearch ->
            DesktopJsonDetail("Arguments", arguments, dim = dim)
    }
}

@Composable
private fun PendingInvalidToolInvocation.renderDesktopDetails(dim: Boolean = false): Unit {
    when (this) {
        is PendingInvalidToolInvocation.Function ->
            DesktopDetail("Arguments", arguments, dim = dim)

        is PendingInvalidToolInvocation.Custom ->
            DesktopDetail("Input", input, dim = dim)

        is PendingInvalidToolInvocation.ToolSearch ->
            DesktopJsonDetail("Arguments", arguments, dim = dim)
    }
}

@Composable
private fun StableCommandExecutionAction.renderDesktopDetails(
    sourceArguments: ExecCommandArguments? = null,
    dim: Boolean = false,
): Unit {
    when (this) {
        is StableCommandExecutionAction.ExecCommand ->
            arguments.renderDesktopDetails(dim)

        is StableCommandExecutionAction.WriteStdin ->
            arguments.renderDesktopDetails(sourceArguments, dim)
    }
}

@Composable
private fun PendingCommandExecutionAction.renderDesktopDetails(
    sourceArguments: ExecCommandArguments? = null,
    dim: Boolean = false,
): Unit {
    when (this) {
        is PendingCommandExecutionAction.ExecCommand ->
            arguments.renderDesktopDetails(dim)

        is PendingCommandExecutionAction.WriteStdin ->
            arguments.renderDesktopDetails(sourceArguments, dim)
    }
}

@Composable
private fun ExecCommandArguments.renderDesktopDetails(dim: Boolean = false): Unit {
    DesktopDetail("Command", command, dim = dim)
    workdir
        ?.takeIf(String::isNotBlank)
        ?.let { DesktopDetail("Working directory", it, dim = dim) }
    shell?.let { DesktopDetail("Shell", it.path.toString(), dim = dim) }
    if (tty) DesktopDetail("TTY", "enabled", dim = dim)
    DesktopDetail("Yield", "${yieldTimeMillis}ms", dim = dim)
    DesktopDetail("Output limit", "$maxOutputTokens tokens", dim = dim)
}

@Composable
private fun WriteStdinArguments.renderDesktopDetails(
    sourceArguments: ExecCommandArguments? = null,
    dim: Boolean = false,
): Unit {
    sourceArguments
        ?.command
        ?.takeIf(String::isNotBlank)
        ?.let { DesktopDetail("Command", it, dim = dim) }
    DesktopDetail("Session", sessionId.toString(), dim = dim)
    DesktopDetail("Input", chars.displayStdin(), dim = dim)
    DesktopDetail("Yield", "${yieldTimeMillis}ms", dim = dim)
    DesktopDetail("Output limit", "$maxOutputTokens tokens", dim = dim)
}

@Composable
private fun StableCommandExecutionResult.renderDesktopDetails(): Unit {
    when (this) {
        is StableCommandExecutionResult.Output -> value.renderDesktopDetails()
        is StableCommandExecutionResult.Failure -> DesktopDetail("Error", message)
    }
}

@Composable
private fun UnifiedExecOutput.renderDesktopDetails(): Unit {
    DesktopDetail("Chunk", chunkId)
    DesktopDetail("Elapsed", "${wallTimeSeconds}s")
    exitCode?.let { DesktopDetail("Exit code", it.toString()) }
    sessionId?.let { DesktopDetail("Session", it.toString()) }
    DesktopDetail("Original output", "$originalTokenCount tokens")
    DesktopDetail("Output", output)
}

@Composable
private fun FunctionCallOutputPayload.renderDesktopDetails(): Unit {
    success?.let { DesktopDetail("Success", it.toString()) }
    when (val value = body) {
        is FunctionCallOutputBody.Text -> DesktopDetail("Result", value.text)
        is FunctionCallOutputBody.ContentItems -> value.items.forEach { item ->
            when (item) {
                is FunctionCallOutputContentItem.InputText ->
                    DesktopDetail("Result", item.text)

                is FunctionCallOutputContentItem.InputImage -> DesktopDetail(
                    "Image",
                    "${item.imageUrl.safeMediaReference()} · " +
                        (item.detail?.name?.lowercase() ?: "auto"),
                )

                is FunctionCallOutputContentItem.EncryptedContent ->
                    DesktopDetail("Result", "[encrypted content]")
            }
        }
    }
}

@Composable
private fun CallToolResult.renderDesktopDetails(): Unit {
    structuredContent?.let { DesktopJsonDetail("Structured result", it) }
    if (content.isEmpty() && structuredContent == null) {
        DesktopDetail("Result", "no content")
    } else {
        content.forEach { item -> item.renderMcpDesktopContent() }
    }
}

@Composable
private fun JsonElement.renderMcpDesktopContent(): Unit {
    val objectValue = this as? JsonObject
    when (objectValue?.stringOrNull("type")) {
        "text" -> DesktopDetail(
            "Content",
            objectValue.stringOrNull("text").orEmpty(),
        )

        "image" -> DesktopDetail("Image", objectValue.binaryReference())
        else -> DesktopJsonDetail("Content", this)
    }
}

@Composable
private fun ImageGenToolArguments.renderDesktopDetails(dim: Boolean = false): Unit {
    DesktopDetail("Prompt", prompt, dim = dim)
    referencedImagePaths
        ?.takeIf { it.isNotEmpty() }
        ?.let { DesktopDetail("Referenced images", it.joinToString(), dim = dim) }
    numLastImagesToInclude
        ?.let { DesktopDetail("Recent images", it.toString(), dim = dim) }
}

@Composable
private fun ViewImageToolArguments.renderDesktopDetails(dim: Boolean = false): Unit {
    DesktopDetail("Path", path, dim = dim)
    DesktopDetail("Detail", detail?.name?.lowercase() ?: "high", dim = dim)
    environmentId
        ?.takeIf(String::isNotBlank)
        ?.let { DesktopDetail("Environment", it, dim = dim) }
}

@Composable
private fun SearchToolCallParams.renderDesktopDetails(dim: Boolean = false): Unit {
    DesktopDetail("Query", query, dim = dim)
    limit?.let { DesktopDetail("Limit", it.toString(), dim = dim) }
}

@Composable
private fun RequestUserInputArgs.renderDesktopDetails(dim: Boolean = false): Unit {
    autoResolutionMs
        ?.let { DesktopDetail("Auto resolution", "${it}ms", dim = dim) }
    questions.forEach { question ->
        DesktopDetail(question.header, question.question, dim = dim)
        question.options
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString { option ->
                if (option.description.isBlank()) {
                    option.label
                } else {
                    "${option.label} (${option.description})"
                }
            }
            ?.let { DesktopDetail("Options", it, dim = dim) }
    }
}

@Composable
private fun RequestUserInputResponse.renderDesktopDetails(
    arguments: RequestUserInputArgs,
): Unit {
    arguments.questions.forEach { question ->
        val answer = answers[question.id] ?: return@forEach
        DesktopDetail(
            label = "Answer ${question.header}",
            value = if (question.isSecret) {
                "[hidden]"
            } else {
                answer.answers.joinToString()
            },
        )
    }
}

@Composable
private fun SearchCommands.renderDesktopDetails(dim: Boolean = false): Unit {
    var hasOperation = false
    searchQuery.orEmpty().forEach { query ->
        hasOperation = true
        DesktopDetail("Search", query.q, dim = dim)
    }
    imageQuery.orEmpty().forEach { query ->
        hasOperation = true
        DesktopDetail("Image search", query.q, dim = dim)
    }
    open.orEmpty().forEach { operation ->
        hasOperation = true
        DesktopDetail(
            "Open",
            operation.refId + operation.lineno?.let { " at line $it" }.orEmpty(),
            dim = dim,
        )
    }
    click.orEmpty().forEach { operation ->
        hasOperation = true
        DesktopDetail("Click", "${operation.refId} #${operation.id}", dim = dim)
    }
    find.orEmpty().forEach { operation ->
        hasOperation = true
        DesktopDetail("Find", "${operation.pattern} in ${operation.refId}", dim = dim)
    }
    screenshot.orEmpty().forEach { operation ->
        hasOperation = true
        DesktopDetail("Screenshot", "${operation.refId} page ${operation.pageno}", dim = dim)
    }
    finance.orEmpty().forEach { operation ->
        hasOperation = true
        DesktopDetail(
            "Finance",
            "${operation.ticker} (${operation.type.name.lowercase()})",
            dim = dim,
        )
    }
    weather.orEmpty().forEach { operation ->
        hasOperation = true
        DesktopDetail("Weather", operation.location, dim = dim)
    }
    sports.orEmpty().forEach { operation ->
        hasOperation = true
        DesktopDetail(
            "Sports",
            "${operation.league.name.lowercase()} ${operation.function.name.lowercase()}",
            dim = dim,
        )
    }
    time.orEmpty().forEach { operation ->
        hasOperation = true
        DesktopDetail("Time", operation.utcOffset, dim = dim)
    }
    responseLength?.let {
        DesktopDetail("Response length", it.name.lowercase(), dim = dim)
    }
    if (!hasOperation) DesktopDetail("Request", "web search", dim = dim)
}

@Composable
private fun WebSearchAction.renderDesktopDetails(): Unit {
    when (this) {
        is WebSearchAction.Search -> {
            query?.takeIf(String::isNotBlank)?.let { DesktopDetail("Search", it) }
            queries
                ?.takeIf { it.isNotEmpty() }
                ?.let { DesktopDetail("Search", it.joinToString()) }
            if (query.isNullOrBlank() && queries.isNullOrEmpty()) {
                DesktopDetail("Search", "")
            }
        }

        is WebSearchAction.OpenPage -> DesktopDetail("Open", url ?: "page")
        is WebSearchAction.FindInPage -> DesktopDetail(
            "Find",
            listOfNotNull(pattern, url).joinToString(" in ").ifBlank { "page" },
        )

        WebSearchAction.Other -> DesktopDetail("Request", "hosted web search")
    }
}

@Composable
private fun PendingMultiAgentInvocation.renderDesktopDetails(dim: Boolean = false): Unit {
    when (this) {
        is PendingMultiAgentInvocation.SpawnAgent ->
            arguments.renderDesktopDetails(dim)

        is PendingMultiAgentInvocation.SendMessage ->
            arguments.renderDesktopDetails(dim)

        is PendingMultiAgentInvocation.FollowupTask ->
            arguments.renderDesktopDetails(dim)

        is PendingMultiAgentInvocation.WaitAgent ->
            arguments.renderDesktopDetails(dim)

        is PendingMultiAgentInvocation.InterruptAgent ->
            arguments.renderDesktopDetails(dim)

        is PendingMultiAgentInvocation.ListAgents ->
            arguments.renderDesktopDetails(dim)
    }
}

@Composable
private fun SpawnAgentArgs.renderDesktopDetails(dim: Boolean = false): Unit {
    DesktopDetail("Task", taskName, dim = dim)
    DesktopDetail("Message", message, dim = dim)
    DesktopDetail("Fork history", forkTurns.displayName(), dim = dim)
    model?.let { DesktopDetail("Model", it.value, dim = dim) }
    reasoningEffort?.let { DesktopDetail("Reasoning", it.wireName, dim = dim) }
    serviceTier?.let { DesktopDetail("Service tier", it.requestValue, dim = dim) }
}

@Composable
private fun SendMessageArgs.renderDesktopDetails(dim: Boolean = false): Unit {
    DesktopDetail("Target", target, dim = dim)
    DesktopDetail("Message", message, dim = dim)
}

@Composable
private fun FollowupTaskArgs.renderDesktopDetails(dim: Boolean = false): Unit {
    DesktopDetail("Target", target, dim = dim)
    DesktopDetail("Message", message, dim = dim)
}

@Composable
private fun WaitAgentArgs.renderDesktopDetails(dim: Boolean = false): Unit {
    DesktopDetail("Timeout", timeoutMs?.let { "${it}ms" } ?: "default", dim = dim)
}

@Composable
private fun InterruptAgentArgs.renderDesktopDetails(dim: Boolean = false): Unit {
    DesktopDetail("Target", target, dim = dim)
}

@Composable
private fun ListAgentsArgs.renderDesktopDetails(dim: Boolean = false): Unit {
    DesktopDetail("Path prefix", pathPrefix ?: "all", dim = dim)
}

@Composable
private fun StableSpawnAgentResult.renderDesktopDetails(): Unit {
    when (this) {
        is StableSpawnAgentResult.Success -> value.renderDesktopDetails()
        is StableSpawnAgentResult.Failure -> DesktopDetail("Error", message)
    }
}

@Composable
private fun SpawnAgentResult.renderDesktopDetails(): Unit {
    DesktopDetail("Agent", taskName)
    nickname?.takeIf(String::isNotBlank)?.let { DesktopDetail("Nickname", it) }
}

@Composable
private fun StableAgentDeliveryResult.renderDesktopDetails(): Unit {
    when (this) {
        is StableAgentDeliveryResult.Success -> DesktopDetail("Result", output)
        is StableAgentDeliveryResult.Failure -> DesktopDetail("Error", message)
    }
}

@Composable
private fun StableWaitAgentResult.renderDesktopDetails(): Unit {
    when (this) {
        is StableWaitAgentResult.Success -> value.renderDesktopDetails()
        is StableWaitAgentResult.Failure -> DesktopDetail("Error", message)
    }
}

@Composable
private fun WaitAgentResult.renderDesktopDetails(): Unit {
    DesktopDetail("Result", message)
    DesktopDetail("Timed out", timedOut.toString())
}

@Composable
private fun StableInterruptAgentResult.renderDesktopDetails(): Unit {
    when (this) {
        is StableInterruptAgentResult.Success -> value.renderDesktopDetails()
        is StableInterruptAgentResult.Failure -> DesktopDetail("Error", message)
    }
}

@Composable
private fun InterruptAgentResult.renderDesktopDetails(): Unit {
    DesktopDetail("Previous status", previousStatus.displayName())
}

@Composable
private fun StableListAgentsResult.renderDesktopDetails(): Unit {
    when (this) {
        is StableListAgentsResult.Success -> value.renderDesktopDetails()
        is StableListAgentsResult.Failure -> DesktopDetail("Error", message)
    }
}

@Composable
private fun ListAgentsResult.renderDesktopDetails(): Unit {
    if (agents.isEmpty()) {
        DesktopDetail("Agents", "none")
    } else {
        agents.forEach { agent ->
            DesktopDetail(
                "Agent",
                "${agent.agentName} · ${agent.agentStatus.displayName()}",
            )
        }
    }
}

@Composable
private fun StreamingTailRow(
    tail: AgentStreamTail,
    uiState: AgentHistoryDesktopUiState,
    expansionKey: Any,
    onContentChange: () -> Unit,
): Unit {
    when (tail) {
        AgentStreamTail.Started -> DesktopHistoryText("Starting response…", dim = true)
        AgentStreamTail.Compacting -> DesktopHistoryText("Compacting context…", dim = true)
        is AgentStreamTail.Output -> {
            val snapshot = rememberDesktopStreamingSnapshot(tail.events, onContentChange)
            when (tail.kind) {
                AgentStreamKind.Message -> DesktopStreamingText(
                    header = "Assistant · streaming",
                    parts = snapshot.messageParts,
                    headerBold = true,
                )

                AgentStreamKind.AgentMessage -> {
                    val header = (snapshot.item as? ResponseItem.AgentMessage)
                        ?.let { "${it.author} → ${it.recipient} · streaming" }
                        ?: "Agent message · streaming"
                    DesktopStreamingText(
                        header = header,
                        parts = snapshot.messageParts,
                        headerBold = true,
                    )
                }

                AgentStreamKind.Reasoning -> DesktopStreamingText(
                    header = "Thinking · streaming",
                    parts = snapshot.reasoningSummaryParts,
                    dim = true,
                )

                AgentStreamKind.ToolCall -> {
                    val presentation = snapshot.toolPresentation()
                    DesktopToolEvent(
                        summary = presentation.summary,
                        rawName = presentation.rawName,
                        status = presentation.status,
                        uiState = uiState,
                        expansionKey = expansionKey,
                        dimDetails = true,
                    ) {
                        section("Input") {
                            snapshot.toolInputParts.forEach { part ->
                                DesktopDetail("Input", part.text, dim = true)
                            }
                        }
                    }
                }

                AgentStreamKind.Unknown -> DesktopHistoryText(
                    snapshot.item?.unknownHeader() ?: "Receiving response item…",
                    dim = true,
                )
            }
        }
    }
}

@Composable
private fun DesktopStreamingText(
    header: String,
    parts: List<DesktopStreamingTextPart>,
    headerBold: Boolean = false,
    dim: Boolean = false,
): Unit {
    Column(Modifier.fillMaxWidth()) {
        DesktopHistoryText(
            value = header,
            dim = dim,
            fontWeight = if (headerBold) FontWeight.Bold else FontWeight.Normal,
        )
        parts.forEach { part -> DesktopHistoryText(part.text, dim = dim) }
    }
}

@Composable
private fun rememberDesktopStreamingSnapshot(
    events: kotlinx.coroutines.flow.SharedFlow<ResponsesStreamEvent>,
    onContentChange: () -> Unit,
): DesktopStreamingResponseSnapshot {
    var snapshot by remember(events) { mutableStateOf(DesktopStreamingResponseSnapshot()) }
    val latestOnContentChange = rememberUpdatedState(onContentChange)
    LaunchedEffect(events) {
        events.collect { event ->
            val updated = snapshot.reduce(event)
            if (updated != snapshot) {
                snapshot = updated
                latestOnContentChange.value()
            }
        }
    }
    return snapshot
}

private data class DesktopStreamingResponseSnapshot(
    val item: ResponseItem? = null,
    val messageParts: List<DesktopStreamingTextPart> = emptyList(),
    val reasoningSummaryParts: List<DesktopStreamingTextPart> = emptyList(),
    val toolInputParts: List<DesktopStreamingTextPart> = emptyList(),
    val webSearchStatus: String? = null,
) {
    fun reduce(event: ResponsesStreamEvent): DesktopStreamingResponseSnapshot = when (event) {
        is ResponsesStreamEvent.OutputItemAdded -> copy(
            item = event.item,
            messageParts = event.item.initialDesktopMessageParts(),
            reasoningSummaryParts = event.item.initialDesktopReasoningSummaryParts(),
            toolInputParts = event.item.initialDesktopToolInputParts(),
        )

        is ResponsesStreamEvent.ContentPartAdded -> event.part.desktopStreamingTextOrNull()
            ?.let { text ->
                copy(messageParts = messageParts.replace(event.contentIndex.toString(), text))
            }
            ?: this

        is ResponsesStreamEvent.ContentPartDone -> event.part.desktopStreamingTextOrNull()
            ?.let { text ->
                copy(messageParts = messageParts.replace(event.contentIndex.toString(), text))
            }
            ?: this

        is ResponsesStreamEvent.OutputTextDelta -> copy(
            messageParts = messageParts.append(event.contentIndex.toString(), event.delta),
        )

        is ResponsesStreamEvent.OutputTextDone -> copy(
            messageParts = messageParts.replace(event.contentIndex.toString(), event.text),
        )

        is ResponsesStreamEvent.ReasoningSummaryTextDelta -> copy(
            reasoningSummaryParts = reasoningSummaryParts.append(
                event.summaryIndex.toString(),
                event.delta,
            ),
        )

        is ResponsesStreamEvent.ReasoningSummaryTextDone -> copy(
            reasoningSummaryParts = reasoningSummaryParts.replace(
                event.summaryIndex.toString(),
                event.text,
            ),
        )

        is ResponsesStreamEvent.ReasoningSummaryPartAdded ->
            (event.part as? ReasoningItemReasoningSummary.SummaryText)
                ?.let { summary ->
                    copy(
                        reasoningSummaryParts = reasoningSummaryParts.replace(
                            event.summaryIndex.toString(),
                            summary.text,
                        ),
                    )
                }
                ?: this

        is ResponsesStreamEvent.ToolCallInputDelta -> {
            val key = item.desktopStreamingToolInputKey() ?: event.callId ?: event.itemId
            if (key == null) {
                this
            } else {
                copy(toolInputParts = toolInputParts.append(key, event.delta))
            }
        }

        is ResponsesStreamEvent.WebSearchCallInProgress ->
            copy(webSearchStatus = "starting")

        is ResponsesStreamEvent.WebSearchCallSearching ->
            copy(webSearchStatus = "searching")

        is ResponsesStreamEvent.WebSearchCallCompleted ->
            copy(webSearchStatus = "completed")

        is ResponsesStreamEvent.ReasoningTextDelta,
        is ResponsesStreamEvent.ReasoningTextDone,
        is ResponsesStreamEvent.Created,
        is ResponsesStreamEvent.InProgress,
        is ResponsesStreamEvent.Metadata,
        is ResponsesStreamEvent.OutputItemDone,
        is ResponsesStreamEvent.Completed,
        is ResponsesStreamEvent.Failed,
        is ResponsesStreamEvent.Incomplete,
        is ResponsesStreamEvent.Other,
            -> this
    }

    fun toolPresentation(): DesktopStreamingToolPresentation =
        item.desktopStreamingToolPresentation(webSearchStatus)
}

private data class DesktopStreamingToolPresentation(
    val summary: String,
    val rawName: String?,
    val status: String,
)

private data class DesktopStreamingTextPart(
    val key: String,
    val text: String,
)

private fun List<DesktopStreamingTextPart>.append(
    key: String,
    delta: String,
): List<DesktopStreamingTextPart> {
    val current = firstOrNull { part -> part.key == key }
    return replace(key, (current?.text ?: "") + delta)
}

private fun List<DesktopStreamingTextPart>.replace(
    key: String,
    text: String,
): List<DesktopStreamingTextPart> {
    val replacement = DesktopStreamingTextPart(key, text)
    val index = indexOfFirst { part -> part.key == key }
    return if (index < 0) {
        this + replacement
    } else {
        mapIndexed { currentIndex, part ->
            if (currentIndex == index) replacement else part
        }
    }
}

private fun ContentItem.desktopStreamingTextOrNull(): String? = when (this) {
    is ContentItem.InputText -> text
    is ContentItem.OutputText -> text
    is ContentItem.InputImage -> "[image]"
}

private fun ResponseItem?.initialDesktopMessageParts(): List<DesktopStreamingTextPart> =
    when (this) {
        is ResponseItem.Message -> content.mapIndexed { index, value ->
            DesktopStreamingTextPart(
                index.toString(),
                value.desktopStreamingTextOrNull().orEmpty(),
            )
        }.filter { part -> part.text.isNotBlank() }

        is ResponseItem.AgentMessage -> content.mapIndexed { index, value ->
            val text = when (value) {
                is AgentMessageInputContent.InputText -> value.text
                is AgentMessageInputContent.EncryptedContent -> "[encrypted content]"
            }
            DesktopStreamingTextPart(index.toString(), text)
        }.filter { part -> part.text.isNotBlank() }

        else -> emptyList()
    }

private fun ResponseItem?.initialDesktopReasoningSummaryParts():
    List<DesktopStreamingTextPart> =
    (this as? ResponseItem.Reasoning)
        ?.summary
        ?.mapIndexedNotNull { index, summary ->
            (summary as? ReasoningItemReasoningSummary.SummaryText)
                ?.text
                ?.takeIf(String::isNotBlank)
                ?.let { DesktopStreamingTextPart(index.toString(), it) }
        }
        .orEmpty()

private fun ResponseItem?.initialDesktopToolInputParts(): List<DesktopStreamingTextPart> {
    val input = when (this) {
        is ResponseItem.FunctionCall -> arguments
        is ResponseItem.CustomToolCall -> input
        is ResponseItem.ClientToolSearchCall -> arguments.toString()
        is ResponseItem.ServerToolSearchCall -> arguments.toString()
        else -> null
    }?.takeIf(String::isNotBlank) ?: return emptyList()
    val key = desktopStreamingToolInputKey() ?: return emptyList()
    return listOf(DesktopStreamingTextPart(key, input))
}

private fun ResponseItem?.desktopStreamingToolPresentation(
    webSearchStatus: String?,
): DesktopStreamingToolPresentation = when (this) {
    is ResponseItem.FunctionCall -> DesktopStreamingToolPresentation(
        summary = functionToolSummary(name, namespace),
        rawName = qualifiedName(name, namespace),
        status = "streaming",
    )

    is ResponseItem.CustomToolCall -> DesktopStreamingToolPresentation(
        summary = functionToolSummary(name, namespace),
        rawName = qualifiedName(name, namespace),
        status = status ?: "streaming",
    )

    is ResponseItem.ClientToolSearchCall -> DesktopStreamingToolPresentation(
        summary = "Search available tools",
        rawName = "tool_search",
        status = status ?: "streaming",
    )

    is ResponseItem.ServerToolSearchCall -> DesktopStreamingToolPresentation(
        summary = "Load tools from the server",
        rawName = "server_tool_search",
        status = status ?: "streaming",
    )

    is ResponseItem.LocalShellCall -> DesktopStreamingToolPresentation(
        summary = "Run a command",
        rawName = "shell",
        status = status.name.lowercase(),
    )

    is ResponseItem.WebSearchCall -> DesktopStreamingToolPresentation(
        summary = "Search the web",
        rawName = "hosted_web_search",
        status = webSearchStatus ?: status ?: "streaming",
    )

    is ResponseItem.ImageGenerationCall -> DesktopStreamingToolPresentation(
        summary = "Generate an image",
        rawName = "hosted_image_generation",
        status = status,
    )

    is ResponseItem.FunctionCallOutput -> DesktopStreamingToolPresentation(
        summary = "Receive a tool result",
        rawName = null,
        status = "streaming",
    )

    is ResponseItem.McpToolCallOutput -> DesktopStreamingToolPresentation(
        summary = "Receive an MCP tool result",
        rawName = null,
        status = "streaming",
    )

    is ResponseItem.CustomToolCallOutput -> DesktopStreamingToolPresentation(
        summary = "Receive a tool result",
        rawName = null,
        status = "streaming",
    )

    is ResponseItem.ClientToolSearchOutput -> DesktopStreamingToolPresentation(
        summary = "Receive available tools",
        rawName = null,
        status = status,
    )

    is ResponseItem.ServerToolSearchOutput -> DesktopStreamingToolPresentation(
        summary = "Receive available tools",
        rawName = null,
        status = status,
    )

    is ResponseItem.AdditionalTools -> DesktopStreamingToolPresentation(
        summary = "Update the available tool catalog",
        rawName = null,
        status = "streaming",
    )

    else -> DesktopStreamingToolPresentation(
        summary = "Run a tool",
        rawName = null,
        status = "streaming",
    )
}

private fun ResponseItem?.desktopStreamingToolInputKey(): String? = when (this) {
    is ResponseItem.ToolCall -> callId
    is ResponseItem.LocalShellCall -> callId ?: id?.value
    is ResponseItem.ServerToolSearchCall -> id?.value
    is ResponseItem.WebSearchCall -> id?.value
    is ResponseItem.ImageGenerationCall -> id?.value
    else -> null
}

private fun ResponseItem.unknownHeader(): String = when (this) {
    is ResponseItem.Message -> "Assistant · streaming"
    is ResponseItem.AgentMessage -> "$author → $recipient · streaming"
    is ResponseItem.Reasoning -> "Thinking · streaming"
    else -> desktopStreamingToolPresentation(null).summary
}

@Composable
private fun HistoryStatus(text: String): Unit {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        DesktopHistoryText(text, dim = true)
    }
}

@Composable
private fun desktopToolHeaderColor(status: String): Color = when (status) {
    "failed" -> MaterialTheme.colorScheme.error
    "running",
    "streaming",
    "starting",
    "in_progress",
    "inprogress",
        -> MaterialTheme.colorScheme.tertiary

    else -> MaterialTheme.colorScheme.onSurface
}

private fun StableCommandExecutionAction.toolName(): String = when (this) {
    is StableCommandExecutionAction.ExecCommand -> "exec_command"
    is StableCommandExecutionAction.WriteStdin -> "write_stdin"
}

private fun StableCommandExecutionResult.status(
    session: AgentShellSession?,
    processCompleted: Boolean,
): String = when (this) {
    is StableCommandExecutionResult.Output -> value.exitCode?.completedStatus()
        ?: if (session == null || processCompleted) "finished" else "running"

    is StableCommandExecutionResult.Failure -> "failed"
}

private fun StableCommandExecutionToolEvent.activeSessionId(): Int? =
    when (val value = action) {
        is StableCommandExecutionAction.ExecCommand ->
            (result as? StableCommandExecutionResult.Output)?.value?.sessionId

        is StableCommandExecutionAction.WriteStdin -> value.arguments.sessionId
    }

private fun PendingCommandExecutionAction.activeSessionId(): Int? = when (this) {
    is PendingCommandExecutionAction.ExecCommand -> null
    is PendingCommandExecutionAction.WriteStdin -> arguments.sessionId
}

@Composable
private fun AgentShellSession?.completedForDesktopPresentation(): Boolean {
    if (this == null) return false
    val completed by completed.collectAsState()
    return completed
}

private fun StableImageGenerationResult.status(): String = when (this) {
    is StableImageGenerationResult.Success -> "succeeded"
    is StableImageGenerationResult.Failure -> "failed"
}

private fun StableImageViewResult.status(): String = when (this) {
    is StableImageViewResult.Success -> "succeeded"
    is StableImageViewResult.Failure -> "failed"
}

private fun StableRequestUserInputResult.status(): String = when (this) {
    is StableRequestUserInputResult.Answered -> "answered"
    is StableRequestUserInputResult.Failure -> "failed"
}

private fun ToolSearchResult.status(): String = when (this) {
    is ToolSearchResult.Success -> "succeeded"
    is ToolSearchResult.InvalidArguments -> "failed"
}

private fun StableWebSearchResult.status(): String = when (this) {
    is StableWebSearchResult.Success -> "succeeded"
    is StableWebSearchResult.Failure -> "failed"
}

private fun StableSpawnAgentResult.completedStatus(): String = when (this) {
    is StableSpawnAgentResult.Success -> "succeeded"
    is StableSpawnAgentResult.Failure -> "failed"
}

private fun StableAgentDeliveryResult.completedStatus(): String = when (this) {
    is StableAgentDeliveryResult.Success -> "succeeded"
    is StableAgentDeliveryResult.Failure -> "failed"
}

private fun StableWaitAgentResult.completedStatus(): String = when (this) {
    is StableWaitAgentResult.Success -> "succeeded"
    is StableWaitAgentResult.Failure -> "failed"
}

private fun StableInterruptAgentResult.completedStatus(): String = when (this) {
    is StableInterruptAgentResult.Success -> "succeeded"
    is StableInterruptAgentResult.Failure -> "failed"
}

private fun StableListAgentsResult.completedStatus(): String = when (this) {
    is StableListAgentsResult.Success -> "succeeded"
    is StableListAgentsResult.Failure -> "failed"
}

private fun Boolean?.completedStatus(): String = when (this) {
    true -> "succeeded"
    false -> "failed"
    null -> "completed"
}

private fun Int?.completedStatus(): String = when (this) {
    null -> "running"
    0 -> "succeeded"
    else -> "failed"
}

private fun CallToolResult.completedStatus(): String = when (isError) {
    true -> "failed"
    false -> "succeeded"
    null -> "completed"
}

private fun InvalidToolInvocation.displayName(): String = when (this) {
    is InvalidToolInvocation.Function -> qualifiedName(name, namespace)
    is InvalidToolInvocation.Custom -> qualifiedName(name, namespace)
    is InvalidToolInvocation.ToolSearch -> "tool_search"
}

private fun PendingInvalidToolInvocation.displayName(): String = when (this) {
    is PendingInvalidToolInvocation.Function -> qualifiedName(name, namespace)
    is PendingInvalidToolInvocation.Custom -> qualifiedName(name, namespace)
    is PendingInvalidToolInvocation.ToolSearch -> "tool_search"
}

private fun SpawnForkMode.displayName(): String = when (this) {
    SpawnForkMode.None -> "none"
    SpawnForkMode.All -> "all"
    is SpawnForkMode.Recent -> "$turns turns"
}

private fun MultiAgentStatus.displayName(): String = when (this) {
    MultiAgentStatus.Running -> "running"
    MultiAgentStatus.Idle -> "idle"
}

private fun LoadableToolSpec.displayName(): String = when (this) {
    is ResponsesApiTool -> name
    is ResponsesApiNamespace -> name
}

private fun qualifiedName(name: String, namespace: String?): String =
    namespace?.takeIf(String::isNotBlank)?.let { "$it.$name" } ?: name

internal fun functionToolSummary(name: String, namespace: String?): String =
    when (qualifiedName(name, namespace)) {
        "exec_command",
        "shell.run",
            -> "Run a command"

        "write_stdin" -> "Send input to a terminal"
        "view_image" -> "View an image"
        "image_gen.imagegen" -> "Generate an image"
        "request_user_input" -> "Ask the user for input"
        "tool_search",
        "server_tool_search",
            -> "Search available tools"

        "web.run",
        "hosted_web_search",
            -> "Search the web"

        "hosted_image_generation" -> "Generate an image"
        "spawn_agent" -> "Start an agent"
        "send_message" -> "Message an agent"
        "followup_task" -> "Continue an agent task"
        "wait_agent" -> "Wait for an agent"
        "interrupt_agent" -> "Interrupt an agent"
        "list_agents" -> "List agents"
        "update_plan" -> "Update the plan"
        "get_context_remaining" -> "Check remaining context"
        "clock.curr_time" -> "Check the current time"
        else -> qualifiedName(name, namespace)
    }

private fun StableCommandExecutionAction.toolSummary(
    sourceArguments: ExecCommandArguments? = null,
): String = when (this) {
    is StableCommandExecutionAction.ExecCommand -> arguments.toolSummary()
    is StableCommandExecutionAction.WriteStdin -> arguments.toolSummary(sourceArguments)
}

private fun PendingCommandExecutionAction.toolSummary(
    sourceArguments: ExecCommandArguments? = null,
): String = when (this) {
    is PendingCommandExecutionAction.ExecCommand -> arguments.toolSummary()
    is PendingCommandExecutionAction.WriteStdin -> arguments.toolSummary(sourceArguments)
}

private fun ExecCommandArguments.toolSummary(): String =
    command.singleLineSummary()
        .takeIf(String::isNotBlank)
        ?.let { "Run command: $it" }
        ?: "Run a command"

private fun WriteStdinArguments.toolSummary(
    sourceArguments: ExecCommandArguments? = null,
): String {
    val command = sourceArguments
        ?.command
        ?.singleLineSummary()
        ?.takeIf(String::isNotBlank)
    return when {
        command == null && chars.isEmpty() -> "Wait for terminal session $sessionId"
        command == null -> "Send input to terminal session $sessionId"
        chars.isEmpty() -> "Read output: $command"
        else -> "Send input: $command"
    }
}

private fun ImageGenToolArguments.toolSummary(): String = prompt.imageGenerationSummary()

private fun String.imageGenerationSummary(): String =
    singleLineSummary()
        .takeIf(String::isNotBlank)
        ?.let { "Generate an image: $it" }
        ?: "Generate an image"

private fun ViewImageToolArguments.toolSummary(): String =
    path.singleLineSummary()
        .takeIf(String::isNotBlank)
        ?.let { "View image: $it" }
        ?: "View an image"

private fun RequestUserInputArgs.toolSummary(): String =
    questions.firstOrNull()
        ?.question
        ?.singleLineSummary()
        ?.takeIf(String::isNotBlank)
        ?.let { "Ask the user: $it" }
        ?: "Ask the user for input"

private fun SearchToolCallParams.toolSummary(): String =
    query.singleLineSummary()
        .takeIf(String::isNotBlank)
        ?.let { "Search available tools: $it" }
        ?: "Search available tools"

private fun SearchCommands.toolSummary(): String = when {
    !searchQuery.isNullOrEmpty() -> searchQuery.orEmpty().first().q.singleLineSummary()
        .takeIf(String::isNotBlank)
        ?.let { "Search the web: $it" }
        ?: "Search the web"

    !imageQuery.isNullOrEmpty() -> imageQuery.orEmpty().first().q.singleLineSummary()
        .takeIf(String::isNotBlank)
        ?.let { "Search images: $it" }
        ?: "Search images"

    !open.isNullOrEmpty() -> "Open a web page"
    !click.isNullOrEmpty() -> "Follow a web link"
    !find.isNullOrEmpty() -> "Find text on a web page"
    !screenshot.isNullOrEmpty() -> "Capture a web page"
    !finance.isNullOrEmpty() -> "Look up market data"
    !weather.isNullOrEmpty() -> "Check the weather"
    !sports.isNullOrEmpty() -> "Check sports information"
    !time.isNullOrEmpty() -> "Check the time"
    else -> "Use web search"
}

private fun WebSearchAction.toolSummary(): String = when (this) {
    is WebSearchAction.Search -> (query ?: queries?.firstOrNull())
        ?.singleLineSummary()
        ?.takeIf(String::isNotBlank)
        ?.let { "Search the web: $it" }
        ?: "Search the web"

    is WebSearchAction.OpenPage -> "Open a web page"
    is WebSearchAction.FindInPage -> "Find text on a web page"
    WebSearchAction.Other -> "Use web search"
}

private fun SpawnAgentArgs.toolSummary(): String =
    "Start agent: ${taskName.singleLineSummary()}"

private fun SendMessageArgs.toolSummary(): String =
    "Message agent: ${target.singleLineSummary()}"

private fun FollowupTaskArgs.toolSummary(): String =
    "Continue task for agent: ${target.singleLineSummary()}"

private fun WaitAgentArgs.toolSummary(): String = "Wait for an agent"

private fun InterruptAgentArgs.toolSummary(): String =
    "Interrupt agent: ${target.singleLineSummary()}"

private fun ListAgentsArgs.toolSummary(): String =
    pathPrefix
        ?.singleLineSummary()
        ?.takeIf(String::isNotBlank)
        ?.let { "List agents under: $it" }
        ?: "List agents"

private fun PendingMultiAgentInvocation.toolSummary(): String = when (this) {
    is PendingMultiAgentInvocation.SpawnAgent -> arguments.toolSummary()
    is PendingMultiAgentInvocation.SendMessage -> arguments.toolSummary()
    is PendingMultiAgentInvocation.FollowupTask -> arguments.toolSummary()
    is PendingMultiAgentInvocation.WaitAgent -> arguments.toolSummary()
    is PendingMultiAgentInvocation.InterruptAgent -> arguments.toolSummary()
    is PendingMultiAgentInvocation.ListAgents -> arguments.toolSummary()
}

private fun String.singleLineSummary(): String =
    lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(" ")

private fun String.displayStdin(): String =
    if (isEmpty()) "[poll]" else replace("\r", "\\r").replace("\n", "\\n")

private fun JsonElement.historyPreview(): String = when (this) {
    is JsonObject -> when {
        containsInlineMedia() -> "[structured data with inline media]"
        stringOrNull("type") == "text" ->
            stringOrNull("text").orEmpty().historyTextPreview()

        stringOrNull("type") == "image" -> binaryReference()
        else -> toString().historyTextPreview()
    }

    is JsonArray -> if (containsInlineMedia()) {
        "[list with inline media]"
    } else {
        toString().historyTextPreview()
    }

    is JsonPrimitive ->
        contentOrNull?.historyTextPreview() ?: toString().historyTextPreview()
}

private fun JsonElement.containsInlineMedia(): Boolean = when (this) {
    is JsonObject ->
        stringOrNull("data") != null ||
            stringOrNull("image_url")?.startsWith("data:", ignoreCase = true) == true ||
            stringOrNull("imageUrl")?.startsWith("data:", ignoreCase = true) == true ||
            values.any(JsonElement::containsInlineMedia)

    is JsonArray -> any(JsonElement::containsInlineMedia)
    is JsonPrimitive -> contentOrNull?.startsWith("data:", ignoreCase = true) == true
}

private fun JsonObject.binaryReference(): String {
    val data = stringOrNull("data")
        ?: stringOrNull("image_url")
        ?: stringOrNull("imageUrl")
    return data?.let { "[inline image, ${it.length} characters]" } ?: "[image]"
}

private fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun String.historyTextPreview(): String = when {
    startsWith("data:", ignoreCase = true) -> safeMediaReference()
    length > MaximumInlineJsonLength ->
        take(MaximumInlineJsonLength - Ellipsis.length) + Ellipsis

    else -> this
}

private fun String.safeMediaReference(): String = when {
    startsWith("data:", ignoreCase = true) -> "[inline data, $length characters]"
    length > MaximumInlineReferenceLength ->
        take(MaximumInlineReferenceLength - Ellipsis.length) + Ellipsis

    else -> this
}

private fun StableCleanEvent.roleLabel(): String? = when (this) {
    is StableCleanEvent.UserMessage -> "You"
    is StableCleanEvent.AssistantMessage ->
        phase?.let { "Assistant · ${it.desktopLabel()}" } ?: "Assistant"

    is StableCleanEvent.DeveloperMessage -> "Developer"
    is StableCleanEvent.AgentMessage -> "$author → $recipient"
    is StableCleanEvent.Reasoning,
    StableCleanEvent.ContextCompaction,
        -> null

    else -> null
}

private fun MessagePhase.desktopLabel(): String = when (this) {
    MessagePhase.Commentary -> "commentary"
    MessagePhase.FinalAnswer -> "final answer"
}

private fun StepStatus.desktopPlanMarker(): String = when (this) {
    StepStatus.Pending -> "[ ]"
    StepStatus.InProgress -> "[>]"
    StepStatus.Completed -> "[x]"
}

private fun StableJsonToolEvent.qualifiedToolName(): String =
    listOfNotNull(namespace, name).joinToString(".")

private fun StableTextToolEvent.qualifiedToolName(): String =
    listOfNotNull(namespace, name).joinToString(".")

private fun UnstableCleanEvent.pendingIdentity(position: Int): String = when (this) {
    is PendingToolEvent -> callId
    else -> "${this::class.simpleName}:$position"
}

private fun AgentHistoryEdgeState.loadableCursor() = when (this) {
    is AgentHistoryEdgeState.Ready -> cursor
    is AgentHistoryEdgeState.Failed -> cursor
    AgentHistoryEdgeState.Exhausted,
    is AgentHistoryEdgeState.Loading,
    AgentHistoryEdgeState.Unresolved,
        -> null
}

private const val HistoryLoadBudget: Int = 50
private const val HistoryPrefetchDistance: Int = 4

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.historyPointerScroll(onScroll: () -> Unit): Modifier =
    onPointerEvent(
        eventType = PointerEventType.Scroll,
        pass = PointerEventPass.Final,
    ) { event ->
        if (event.changes.any { change -> change.scrollDelta.y != 0f }) onScroll()
    }

private fun androidx.compose.foundation.lazy.LazyListState.isAtLatestHistoryEdge(): Boolean =
    !canScrollForward

internal suspend fun androidx.compose.foundation.lazy.LazyListState.pageHistory(
    towardOlder: Boolean,
    uiState: AgentHistoryDesktopUiState,
    entryFocusRequesters: Map<StoredDesktopHistoryKey, FocusRequester>,
): Unit {
    val viewportHeight = layoutInfo.viewportSize.height
    if (viewportHeight <= 0) return
    uiState.beginPageScroll(towardOlder)
    val requestedDelta = (viewportHeight / 2).coerceAtLeast(1).toFloat() *
        if (towardOlder) -1f else 1f
    val consumedDelta = scrollBy(requestedDelta)
    if (consumedDelta == 0f) {
        uiState.recordPageScroll(
            towardOlder = towardOlder,
            atLatest = isAtLatestHistoryEdge(),
        )
        return
    }

    yield()
    val atLatest = isAtLatestHistoryEdge()
    uiState.recordPageScroll(towardOlder = towardOlder, atLatest = atLatest)
    val targetKey = layoutInfo.desktopHistoryPageFocusKey(towardTop = towardOlder)
        ?: return
    entryFocusRequesters[targetKey]?.requestFocus()
}

internal fun LazyListLayoutInfo.desktopHistoryPageFocusKey(
    towardTop: Boolean,
): StoredDesktopHistoryKey? {
    val candidates = visibleItemsInfo.filter { item ->
        item.key is StoredDesktopHistoryKey &&
            item.offset >= viewportStartOffset &&
            item.offset + item.size <= viewportEndOffset
    }
    val target = if (towardTop) {
        candidates.minByOrNull { item -> item.offset }
    } else {
        candidates.maxByOrNull { item -> item.offset + item.size }
    }
    return target?.key as? StoredDesktopHistoryKey
}

internal data class StoredDesktopHistoryKey(
    val agentId: String,
    val generation: Long,
    val storageIndex: Int,
    val providerId: String?,
)

internal data class StoredDesktopHistoryItem(
    val key: StoredDesktopHistoryKey,
    val entry: AgentHistoryEntry,
)

private data class PendingDesktopHistoryKey(
    val agentId: String,
    val generation: Long,
    val identity: String,
)

private data class StreamingDesktopHistoryKey(
    val agentId: String,
    val identity: Any,
)

private fun AgentStreamTail.desktopHistoryIdentity(): Any = when (this) {
    AgentStreamTail.Started -> StreamingStartedDesktopHistoryKey
    is AgentStreamTail.Output -> events
    AgentStreamTail.Compacting -> CompactingDesktopHistoryKey
}

private data object StreamingStartedDesktopHistoryKey

private data object CompactingDesktopHistoryKey

private data class DesktopExpansionKey(
    val prefix: Any,
    val identity: Any,
)

private const val MaximumInlineReferenceLength: Int = 160
private const val MaximumInlineJsonLength: Int = 512
private const val Ellipsis: String = "…"
