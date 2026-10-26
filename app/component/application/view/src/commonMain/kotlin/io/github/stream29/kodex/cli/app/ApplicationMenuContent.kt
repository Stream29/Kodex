package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.*
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.cli.agent.canEditHistory
import io.github.stream29.kodex.cli.components.formatPopupTimestamp
import io.github.stream29.kodex.openai.ContentItem
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
        add("Index: $sessionIndex")
        createdAt?.let { add("Created at: $it") }
        updatedAt?.let { add("Updated at: $it") }
    }),
    ApplicationMenuEntry.Action("rename", "Rename", onRename),
    ApplicationMenuEntry.Action("close", "Close", onClose),
    ApplicationMenuEntry.Action("close-and-archive", "Close and archive", onCloseAndArchive),
    ApplicationMenuEntry.Action("delete", "Delete", onDelete),
)

public actual fun applicationHistoryMenuEntries(
    messageIndex: Int?, timestamp: String?, onRevert: () -> Unit, onFork: () -> Unit,
    onRevertAndEdit: (() -> Unit)?,
): List<ApplicationMenuEntry> = buildList {
    if (messageIndex != null) add(ApplicationMenuEntry.Information("history-index-information", buildList {
        add("Index: $messageIndex")
        timestamp?.let { add("Timestamp: $it") }
    }))
    add(ApplicationMenuEntry.Action("revert-to-here", "Revert to here", onRevert))
    add(ApplicationMenuEntry.Action("fork-from-here", "Fork from here", onFork))
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
    val created = rememberApplicationMenuTimestamp(current) { current.target.readCreatedAt() }
    val updated = rememberApplicationMenuTimestamp(current) { current.target.readUpdatedAt() }
    val entries = applicationTabMenuEntries(
        current.target.sessionIndex, created, updated,
        onRename = { onRename(current) }, onClose = { onClose(current) },
        onCloseAndArchive = { onCloseAndArchive(current) }, onDelete = { onDelete(current) },
    )
    key(current) { renderer.Tab(current.placement, entries) { onDismiss(current) } }
}

@Composable
public actual fun ApplicationHistoryMenuContent(
    opening: ApplicationHistoryMenuOpening?,
    selectedSession: PersistedSessionViewModel?,
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
    val targetMatches = current.session === selectedSession && current.agent === selectedAgent &&
        state.canEditHistory(running) && window.size > 0 && current.generation == window.generation &&
        current.agent.history.contains(current.generation, current.storageIndex)
    val placed = renderer.isHistoryPlaced(current.placement)
    LaunchedEffect(current, targetMatches, placed) { if (!targetMatches || !placed) onDismiss(current) }
    if (!targetMatches || !placed) return
    val message = current.item as? MessageHistoryItemViewModel
    val messageState = message?.state?.collectAsState()?.value
    val text = messageState.revertAndEditText()
    val timestamp = rememberApplicationMenuTimestamp(current) { message?.readTimestamp() }
    val entries = applicationHistoryMenuEntries(
        message?.index, timestamp, { onRevert(current) }, { onFork(current) },
        text?.let { value -> { onRevertAndEdit(current, value) } },
    )
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
    DisposableEffect(agent, confirm.requestId) { onDispose { agent.dismissHistoryRevert(confirm.requestId) } }
    key(ConfirmationKey(agent, confirm.requestId)) {
        renderer.RevertConfirmation(
            "Revert history to here?",
            listOf("Keep the selected history entry and remove everything after it.", "This cannot be undone."),
            "Cancel", "Revert",
            onCancel = { agent.dismissHistoryRevert(confirm.requestId) },
            onConfirm = {
                try { agent.confirmHistoryRevert(confirm.requestId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Throwable) { /* The Agent already reports rejected and failed reverts. */ }
            },
        )
    }
}

public actual fun MessageHistoryItemState?.revertAndEditText(): String? {
    val message = (this as? MessageHistoryItemState.Ready)?.event as? StableUserMessage ?: return null
    if (message.content.any { it is ContentItem.InputImage }) return null
    return message.content.joinToString("") {
        when (it) {
            is ContentItem.InputText -> it.text
            is ContentItem.OutputText -> it.text
            is ContentItem.InputImage -> error("Image content cannot be edited in the text composer.")
        }
    }
}

public actual suspend fun revertAndEdit(agent: AgentViewModel, storageIndex: Int, generation: Long, text: String) {
    agent.revertHistory(storageIndex, generation)
    agent.composer.update(text)
}

private class ConfirmationKey(private val owner: AgentViewModel, private val requestId: Long) {
    override fun equals(other: Any?): Boolean = other is ConfirmationKey && other.owner === owner && other.requestId == requestId
    override fun hashCode(): Int = requestId.hashCode()
}
