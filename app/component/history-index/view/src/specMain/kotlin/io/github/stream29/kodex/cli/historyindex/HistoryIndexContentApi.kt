package io.github.stream29.kodex.cli.historyindex

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.cli.history.RequestUserInputHistoryRowModel
import io.github.stream29.kodex.cli.history.HistoryPlanModel
import kotlin.time.Instant

/** Placement only, not Agent identity. */
public enum class HistoryIndexSide { Left, Right }

public data class HistoryIndexRowKey(public val generation: Long, public val revision: Long, public val index: Int)

/** Captured UI admission target; revision is local read invalidation, not a backend credential. */
public class HistoryIndexTarget(
    public val viewModel: HistoryIndexViewModel, public val generation: Long,
    public val index: Int, public val revision: Long,
)

public expect fun HistoryIndexTarget.isCurrent(): Boolean

/** Opaque renderer placement, with no terminal/pixel geometry in shared composition. */
public interface HistoryIndexInteraction {
    public val target: HistoryIndexTarget
    public val isPlaced: Boolean
}

public interface HistoryIndexRowInteraction : HistoryIndexInteraction {
    public fun emitHover(hovered: Boolean)
    public fun emitMenu()
}

/** Fresh object identity owns its timestamp and read-only detail leases. */
public class HistoryIndexMenuOpening(public val interaction: HistoryIndexInteraction)

/** Position within the current linear window, not an Agent relationship. */
public enum class HistoryIndexRowPosition { Single, First, Middle, Last }

public sealed interface HistoryIndexRowValue {
    public data object Loading : HistoryIndexRowValue
    public data object Failed : HistoryIndexRowValue
    public data class Ready(public val summary: String) : HistoryIndexRowValue
}

public data class HistoryIndexRowPresentation(
    public val value: HistoryIndexRowValue,
    public val position: HistoryIndexRowPosition,
)

/** Question rows are already redacted; raw fallback content is empty when those rows are present. */
public data class HistoryIndexDetailPresentation(
    public val title: String, public val content: String,
    public val questionRows: List<RequestUserInputHistoryRowModel>?, public val failed: Boolean,
    public val plan: HistoryPlanModel? = null,
)

public expect fun historyIndexRowPresentation(
    state: HistoryIndexReadState<HistoryIndexEntry>, position: HistoryIndexRowPosition,
): HistoryIndexRowPresentation?

public expect fun historyIndexDetailPresentation(
    state: HistoryIndexReadState<HistoryIndexEntryDetail>,
): HistoryIndexDetailPresentation?

public interface HistoryIndexListScope {
    public fun Rows(window: HistoryIndexWindow, content: @Composable (index: Int) -> Unit)
}

public interface HistoryIndexRenderer {
    public val canShowHover: Boolean
    @Composable public fun List(
        viewModel: HistoryIndexViewModel, window: HistoryIndexWindow, content: (HistoryIndexListScope) -> Unit,
    )
    @Composable public fun rememberInteraction(target: HistoryIndexTarget, side: HistoryIndexSide): HistoryIndexRowInteraction
    @Composable public fun Row(
        interaction: HistoryIndexRowInteraction, presentation: HistoryIndexRowPresentation,
        onHoverChanged: (Boolean) -> Unit, onOpenMenu: () -> Unit,
    )
    @Composable public fun Hover(
        interaction: HistoryIndexInteraction, presentation: HistoryIndexDetailPresentation, onHoverChanged: (Boolean) -> Unit,
    )
    /** Detail is already redacted; null means loading. A command dismisses before onCheckOut. */
    @Composable public fun Menu(
        opening: HistoryIndexMenuOpening, timestamp: Instant?, detail: HistoryIndexDetailPresentation?,
        onDismiss: () -> Unit, onCheckOut: () -> Unit,
    )
}

/** Borrows the VM and acquires/releases an independent row handle for every composed row. */
@Composable
public expect fun HistoryIndexContent(
    viewModel: HistoryIndexViewModel, side: HistoryIndexSide, renderer: HistoryIndexRenderer,
)

@Composable
public expect fun HistoryIndexHoverContent(
    interaction: HistoryIndexInteraction?, renderer: HistoryIndexRenderer,
    onHoverChanged: (Boolean) -> Unit, onDismiss: () -> Unit,
)

@Composable
public expect fun HistoryIndexMenuContent(
    opening: HistoryIndexMenuOpening?, renderer: HistoryIndexRenderer, onDismiss: () -> Unit,
)
