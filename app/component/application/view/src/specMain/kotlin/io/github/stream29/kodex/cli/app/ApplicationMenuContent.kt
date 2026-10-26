package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemState
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabMenuPlacement
import kotlin.time.Instant

/** View-owned origin, not a storage/RPC target or ViewModel field. */
public interface ApplicationHistoryMenuPlacement

/** Each instance is a fresh opening bound to the actual rendered child and storage boundary. */
public class ApplicationHistoryMenuOpening(
    public val session: PersistedSessionViewModel,
    public val agent: AgentViewModel,
    public val generation: Long,
    public val storageIndex: Int,
    public val item: HistoryItemViewModel,
    public val placement: ApplicationHistoryMenuPlacement,
)

/** Immutable frame projection; callbacks already retain their exact target. */
public sealed interface ApplicationMenuEntry {
    public val key: String
    public data class Information(override val key: String, public val lines: List<String>) : ApplicationMenuEntry
    public data class Action(override val key: String, public val label: String, public val onClick: () -> Unit) : ApplicationMenuEntry
}

/**
 * Menu DSL, focus, origin liveness and modal geometry; no command or optional-field authority.
 * Leaf callbacks admit and dismiss their exact opening before starting work, not after an automatic close.
 */
public interface ApplicationMenuRenderer {
    public fun isTabPlaced(placement: SessionTabMenuPlacement): Boolean
    public fun isHistoryPlaced(placement: ApplicationHistoryMenuPlacement): Boolean
    @Composable public fun Tab(
        placement: SessionTabMenuPlacement, entries: List<ApplicationMenuEntry>, onDismiss: () -> Unit,
    )
    @Composable public fun History(
        placement: ApplicationHistoryMenuPlacement, entries: List<ApplicationMenuEntry>, onDismiss: () -> Unit,
    )
    /** Destructive confirmation with initial focus on Cancel. */
    @Composable public fun RevertConfirmation(
        title: String, paragraphs: List<String>, cancelLabel: String, confirmLabel: String,
        onCancel: () -> Unit, onConfirm: () -> Unit,
    )
}

public expect fun applicationTabMenuEntries(
    sessionIndex: Int, createdAt: String?, updatedAt: String?,
    onRename: () -> Unit, onClose: () -> Unit, onCloseAndArchive: () -> Unit, onDelete: () -> Unit,
): List<ApplicationMenuEntry>

public expect fun applicationHistoryMenuEntries(
    messageIndex: Int?, timestamp: String?, onRevert: () -> Unit, onFork: () -> Unit,
    onRevertAndEdit: (() -> Unit)?,
): List<ApplicationMenuEntry>

/** One optional formatted read per opening; late cancelled work cannot publish into a new opening. */
@Composable
public expect fun rememberApplicationMenuTimestamp(request: Any, read: suspend () -> Instant?): String?

@Composable
public expect fun ApplicationTabMenuContent(
    opening: ApplicationTabMenuTarget?,
    targetIsOpen: Boolean,
    renderer: ApplicationMenuRenderer,
    onDismiss: (ApplicationTabMenuTarget) -> Unit,
    onClose: (ApplicationTabMenuTarget) -> Unit,
    onCloseAndArchive: (ApplicationTabMenuTarget) -> Unit,
    onRename: (ApplicationTabMenuTarget) -> Unit,
    onDelete: (ApplicationTabMenuTarget) -> Unit,
): Unit

@Composable
public expect fun ApplicationHistoryMenuContent(
    opening: ApplicationHistoryMenuOpening?,
    selectedSession: PersistedSessionViewModel?,
    selectedAgent: AgentViewModel?,
    renderer: ApplicationMenuRenderer,
    onDismiss: (ApplicationHistoryMenuOpening) -> Unit,
    onRevert: (ApplicationHistoryMenuOpening) -> Unit,
    onRevertAndEdit: (ApplicationHistoryMenuOpening, String) -> Unit,
    onFork: (ApplicationHistoryMenuOpening) -> Unit,
): Unit

/** Existing confirmation admission/disposal; accepted work remains owned by the Agent. */
@Composable
public expect fun AgentHistoryRevertContent(agent: AgentViewModel?, renderer: ApplicationMenuRenderer): Unit

/** Only original entirely textual User content can round-trip, retaining whitespace and newlines. */
public expect fun MessageHistoryItemState?.revertAndEditText(): String?

/** Reverts the captured owner before replacing its draft; never automatically submits or retries. */
public expect suspend fun revertAndEdit(agent: AgentViewModel, storageIndex: Int, generation: Long, text: String): Unit
