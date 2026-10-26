package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemViewModel
import kotlin.time.Duration

/** Item geometry, focus, context-menu anchors and expansion mounting; never a second row state machine. */
public interface HistoryItemRenderer : HistoryContentRenderer {
    @Composable public fun Loading()
    @Composable public fun Error()
    @Composable public fun ItemHeading(value: String, elapsed: Duration, style: HistoryTextStyle)
    @Composable public fun TurnMarker(duration: Duration)
    @Composable public fun Entry(item: HistoryItemViewModel, storageIndex: Int, content: @Composable () -> Unit)
    @Composable public fun GroupHeading(
        group: WorkGroupHistoryItemViewModel, value: String, elapsed: Duration, onClick: () -> Unit,
    )
    @Composable public fun Collapsed(summary: String, status: String, elapsed: Duration, onClick: () -> Unit)
    /** A non-null collapse callback mounts the event with externally controlled expanded=true. */
    @Composable public fun Event(
        event: StableCleanEvent, elapsed: Duration, onCollapse: (() -> Unit)?,
        shellSessions: AgentShellSessionRegistry?,
    )
}

/** Reads the exact committed child, including WorkGroup children; no cache, I/O or owner disposal. */
@Composable
public expect fun HistoryItemContent(
    item: HistoryItemViewModel, renderer: HistoryItemRenderer, shellSessions: AgentShellSessionRegistry? = null,
)
