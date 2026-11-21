package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.*
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.AgentTabViewModel
import io.github.stream29.kodex.cli.agent.canEditHistory
import io.github.stream29.kodex.cli.components.formatPopupTimestamp
import io.github.stream29.kodex.app.agent.contract.editableHistoryText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.datetime.TimeZone
import kotlin.coroutines.coroutineContext
import kotlin.time.Instant

public actual fun applicationTabMenuEntries(
    sessionIndex: Int, createdAt: String?, updatedAt: String?,
    onRename: () -> Unit, onClose: () -> Unit, onCloseAndArchive: () -> Unit, onDelete: () -> Unit,
): List<ApplicationMenuEntry> = listOf(
    ApplicationMenuEntry.Information("session-index-information", buildList {
        add("Session #$sessionIndex")
        createdAt?.let { add("Created at: $it") }
        updatedAt?.let { add("Updated at: $it") }
    }),
    ApplicationMenuEntry.Action("rename", "Rename Session", onRename),
    ApplicationMenuEntry.Action("close", "Close Agent tab", onClose),
    ApplicationMenuEntry.Action("close-and-archive", "Archive Session and close tab", onCloseAndArchive),
    ApplicationMenuEntry.Action("delete", "Delete Session", onDelete),
)

public actual fun applicationHistoryMenuEntries(
    messageIndex: Int?, timestamp: String?, onRevert: () -> Unit, onFork: (() -> Unit)?,
    onRevertAndEdit: (() -> Unit)?,
): List<ApplicationMenuEntry> = buildList {
    if (messageIndex != null) add(ApplicationMenuEntry.Information("history-index-information", buildList {
        add("Index: $messageIndex")
        timestamp?.let { add("Timestamp: $it") }
    }))
    add(ApplicationMenuEntry.Action("revert-to-here", "Revert to here", onRevert))
    onFork?.let { add(ApplicationMenuEntry.Action("fork-from-here", "Fork from here", it)) }
    onRevertAndEdit?.let { add(ApplicationMenuEntry.Action("revert-and-edit", "Revert and edit", it)) }
}

@Composable
public actual fun rememberApplicationMenuTimestamp(request: Any, read: suspend () -> Instant?): String? {
    var value by remember(request) { mutableStateOf<String?>(null) }
    LaunchedEffect(request) {
        val loaded = try { read()?.let { formatPopupTimestamp(it, TimeZone.currentSystemDefault()) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) { null }
        coroutineContext.ensureActive()
        value = loaded
    }
    return value
}

@Composable
public actual fun ApplicationTabMenuContent(
    opening: ApplicationTabMenuTarget?,
    targetIsOpen: Boolean,
    renderer: ApplicationMenuRenderer,
    onDismiss: (ApplicationTabMenuTarget) -> Unit,
    onClose: (ApplicationTabMenuTarget) -> Unit,
    onCloseAndArchive: (ApplicationTabMenuTarget) -> Unit,
    onRename: (ApplicationTabMenuTarget) -> Unit,
    onDelete: (ApplicationTabMenuTarget) -> Unit,
) {
    val current = opening ?: return
    val valid = targetIsOpen && renderer.isTabPlaced(current.placement)
    LaunchedEffect(current, valid) { if (!valid) onDismiss(current) }
    if (!valid) return
    val name = current.target.name.collectAsState().value
    val root = current.target as? PersistedSessionViewModel
    val entries = listOf(ApplicationMenuEntry.Information("agent-name-information", listOf(name, "Agent #${current.target.agentId}"))) +
        if (root == null) listOf(ApplicationMenuEntry.Action("close", "Close Agent tab") { onClose(current) })
        else {
            val created = rememberApplicationMenuTimestamp(current) { root.readCreatedAt() }
            val updated = rememberApplicationMenuTimestamp(current) { root.readUpdatedAt() }
            applicationTabMenuEntries(
                root.sessionIndex, created, updated,
                onRename = { onRename(current) }, onClose = { onClose(current) },
                onCloseAndArchive = { onCloseAndArchive(current) }, onDelete = { onDelete(current) },
            )
        }
    key(current) { renderer.Tab(current.placement, entries) { onDismiss(current) } }
}

@Composable
public actual fun ApplicationHistoryMenuContent(
    opening: ApplicationHistoryMenuOpening?,
    selectedTab: AgentTabViewModel?,
    selectedAgent: AgentViewModel?,
    renderer: ApplicationMenuRenderer,
    onDismiss: (ApplicationHistoryMenuOpening) -> Unit,
    onRevert: (ApplicationHistoryMenuOpening) -> Unit,
    onRevertAndEdit: (ApplicationHistoryMenuOpening, String) -> Unit,
    onFork: (ApplicationHistoryMenuOpening) -> Unit,
) {
    val current = opening ?: return
    val state = current.agent.state.collectAsState().value
    val running = current.agent.running.collectAsState().value
    val window = current.agent.history.historyItems.collectAsState().value
    val message = current.item as? MessageHistoryItemViewModel
    val messageState = message?.state?.collectAsState()?.value
    val display = current.displayOption.takeIf {
        (messageState as? MessageHistoryItemState.Ready)?.event is StableAssistantMessage
    }
    val canEdit = state.canEditHistory(running)
    val targetMatches = current.tab === selectedTab && current.agent === selectedAgent &&
        (canEdit || display != null) && window.size > 0 && current.generation == window.generation &&
        current.agent.history.contains(current.generation, current.storageIndex)
    val placed = renderer.isHistoryPlaced(current.placement)
    LaunchedEffect(current, targetMatches, placed) { if (!targetMatches || !placed) onDismiss(current) }
    if (!targetMatches || !placed) return
    val text = messageState.revertAndEditText()
    val timestamp = if (canEdit) rememberApplicationMenuTimestamp(current) { message?.readTimestamp() } else null
    val entries = buildList {
        if (canEdit) addAll(applicationHistoryMenuEntries(
            message?.index, timestamp, { onRevert(current) },
            { onFork(current) },
            text?.let { value -> { onRevertAndEdit(current, value) } },
        ))
        display?.let { add(ApplicationMenuEntry.Action("history-display-mode", it.label, it.onToggle)) }
    }
    key(current) { renderer.History(current.placement, entries) { onDismiss(current) } }
}

@Composable
public actual fun AgentHistoryRevertContent(agent: AgentViewModel?, renderer: ApplicationMenuRenderer) {
    if (agent == null) return
    val action = agent.historyAction.collectAsState().value
    val confirm = action as? AgentHistoryActionState.ConfirmRevert ?: return
    val state = agent.state.collectAsState().value
    val running = agent.running.collectAsState().value
    val window = agent.history.historyItems.collectAsState().value
    val matches = state.canEditHistory(running) && confirm.expectedGeneration == window.generation
    LaunchedEffect(agent, confirm.requestId, matches) { if (!matches) agent.dismissHistoryRevert(confirm.requestId) }
    if (!matches) return
    var mounted by remember(ConfirmationKey(agent, confirm.requestId)) { mutableStateOf(false) }
    DisposableEffect(agent, confirm.requestId) {
        mounted = true
        onDispose {
            mounted = false
            agent.dismissHistoryRevert(confirm.requestId)
        }
    }
    key(ConfirmationKey(agent, confirm.requestId)) {
        renderer.RevertConfirmation(
            "Revert history to here?",
            listOf("Keep the selected history entry and remove everything after it.", "This cannot be undone."),
            "Cancel", "Revert",
            onCancel = { agent.dismissHistoryRevert(confirm.requestId) },
            onConfirm = {
                try {
                    if (mounted && agent.historyAction.value === confirm) agent.confirmHistoryRevert(confirm.requestId)
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Throwable) { /* The Agent already reports rejected and failed reverts. */ }
            },
        )
    }
}

public actual fun MessageHistoryItemState?.revertAndEditText(): String? {
    val message = (this as? MessageHistoryItemState.Ready)?.event as? StableUserMessage ?: return null
    return message.editableHistoryText()
}

public actual suspend fun revertAndEdit(agent: AgentViewModel, storageIndex: Int, generation: Long, text: String) {
    agent.revertHistory(storageIndex, generation)
    agent.composer.update(text)
}

private class ConfirmationKey(private val owner: AgentViewModel, private val requestId: Long) {
    override fun equals(other: Any?): Boolean = other is ConfirmationKey && other.owner === owner && other.requestId == requestId
    override fun hashCode(): Int = requestId.hashCode()
}
