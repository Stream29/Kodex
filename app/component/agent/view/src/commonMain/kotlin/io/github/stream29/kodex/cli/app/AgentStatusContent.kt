package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.cli.agent.AgentRuntimeControl
import io.github.stream29.kodex.cli.agent.canCompact
import io.github.stream29.kodex.cli.agent.label
import io.github.stream29.kodex.cli.agent.runtimeControl
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.rpc.models.AgentStateValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Composable
public actual fun AgentRuntimeStatusContent(
    viewModel: AgentViewModel,
    state: AgentStateValue,
    running: Boolean,
    settings: KodexAgentSettings,
    tokenCount: Long?,
    renderer: AgentStatusRenderer,
    onBrowseWorkingDirectory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    renderer.Frame(
        regular = {
            tokenCount?.let { renderer.Text("${it}t") }
            val control = state.runtimeControl(running)
            renderer.Action(control.label(), true) {
                when (control) {
                    AgentRuntimeControl.Stop -> viewModel.cancel()
                    AgentRuntimeControl.ClearPending -> viewModel.clearPending()
                    AgentRuntimeControl.Resume -> viewModel.resume()
                }
            }
            if (compactVisible(running)) {
                renderer.Action("Compact", state.canCompact(running), viewModel::forceCompact)
            }
            renderer.Configuration(viewModel.runtimeConfiguration)
            renderer.Directory(settings.cwd, onBrowseWorkingDirectory)
        },
        settings = { renderer.Settings(onOpenSettings) },
    )
}

public actual fun compactVisible(running: Boolean): Boolean = !running

@Composable
public actual fun AgentHistorySeparatorContent(
    liveDuration: Duration?,
    showScrollToLatest: Boolean,
    onScrollToLatest: () -> Unit,
    renderer: AgentHistorySeparatorRenderer,
) {
    renderer.Separator(liveDuration?.let(::liveTurnDurationLabel), showScrollToLatest, onScrollToLatest)
}

public actual fun liveTurnDurationLabel(duration: Duration): String = "Worked for ${duration.roundToSeconds()}"

public actual fun Duration.roundToSeconds(): Duration {
    if (!isFinite()) return this
    val truncatedSeconds = inWholeSeconds
    val remainder = this - truncatedSeconds.seconds
    val roundedSeconds = when {
        remainder >= 500.milliseconds -> truncatedSeconds + 1
        remainder <= (-500).milliseconds -> truncatedSeconds - 1
        else -> truncatedSeconds
    }
    return roundedSeconds.seconds
}
