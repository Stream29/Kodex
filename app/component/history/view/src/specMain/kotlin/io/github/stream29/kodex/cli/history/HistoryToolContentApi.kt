package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem
import io.github.stream29.kodex.utils.applypatch.Patch
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.WriteStdinArguments
import kotlin.time.Duration

public enum class HistoryTextStyle { Normal, Dim, Bold }

/** Disclosure/measurement only. Text preserves empty output, whitespace and line breaks. */
public interface HistoryToolDetails {
    @Composable public fun Text(value: String, style: HistoryTextStyle)
    @Composable public fun Section(label: String, content: @Composable () -> Unit)
}

public interface HistoryContentRenderer : HistoryFormRenderer {
    @Composable public fun Heading(value: String, style: HistoryTextStyle)
    @Composable public fun Text(value: String, style: HistoryTextStyle)
    @Composable public fun Tool(
        summary: String, rawName: String?, status: String, expansionKey: Any,
        detailStyle: HistoryTextStyle, expandedSummary: String?,
        content: @Composable (HistoryToolDetails) -> Unit,
    )
    @Composable public fun Expandable(
        header: String, expansionKey: Any, style: HistoryTextStyle, content: @Composable () -> Unit,
    )
    @Composable public fun StablePatch(event: StablePatchToolEvent)
    @Composable public fun PendingPatch(diff: Patch)
}

/** Shared exhaustive content dispatch. Shell sessions are borrowed, never started or closed here. */
@Composable
public expect fun HistoryEventContent(
    event: StableCleanEvent, renderer: HistoryContentRenderer,
    shellSessions: AgentShellSessionRegistry? = null, elapsed: Duration? = null,
)

@Composable
public expect fun HistoryEventContent(
    event: UnstableCleanEvent, renderer: HistoryContentRenderer,
    shellSessions: AgentShellSessionRegistry? = null,
)

/** Same replay accumulator and scheduling for text, tool-input and unknown streaming tails. */
@Composable
public expect fun HistoryStreamingContent(
    item: HistoryStreamingItem, renderer: HistoryContentRenderer, onContentChange: () -> Unit = {},
)

@Composable
public expect fun HistoryPlanContent(event: StablePlanUpdate, renderer: HistoryContentRenderer)
@Composable
public expect fun HistoryPlanContent(event: PendingPlanUpdate, renderer: HistoryContentRenderer)

/**
 * Recorded command metadata/output only; never opens a process or a live Shell owner.
 * Omit chunk/session identifiers. A null exit code stays unspecified, and nonempty
 * stdin (including whitespace) and empty output remain meaningful content.
 */
@Composable
public expect fun HistoryCommandContent(event: StableCommandExecutionToolEvent, renderer: HistoryContentRenderer)

public expect fun ExecCommandArguments.expandedCommandContext(): String
public expect fun WriteStdinArguments.expandedCommandContext(): String
public expect fun compactToolLine(name: String, vararg fields: Pair<String, Any?>): String

/** Same nearest-millisecond rounding as the History turn marker; infinite values stay infinite. */
public expect fun Duration.historyElapsedSuffix(): String
