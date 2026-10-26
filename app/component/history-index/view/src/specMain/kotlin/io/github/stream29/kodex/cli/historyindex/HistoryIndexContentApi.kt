package io.github.stream29.kodex.cli.historyindex

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.cli.history.RequestUserInputHistoryRowModel
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

/** Fresh object identity owns one timestamp read, even when reopening the same row. */
public class HistoryIndexMenuOpening(public val interaction: HistoryIndexInteraction)

public data class HistoryIndexRowPresentation(public val value: String, public val failed: Boolean)

/** Question rows are already redacted; raw fallback content is empty when those rows are present. */
public data class HistoryIndexDetailPresentation(
    public val title: String, public val content: String,
    public val questionRows: List<RequestUserInputHistoryRowModel>?, public val failed: Boolean,
)

public expect fun historyIndexRowPresentation(
    state: HistoryIndexReadState<HistoryIndexEntry>, graph: String,
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
    /** Local formatting/geometry only; a command item dismisses before invoking onCheckOut, like Native. */
    @Composable public fun Menu(
        opening: HistoryIndexMenuOpening, timestamp: Instant?, onDismiss: () -> Unit, onCheckOut: () -> Unit,
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
