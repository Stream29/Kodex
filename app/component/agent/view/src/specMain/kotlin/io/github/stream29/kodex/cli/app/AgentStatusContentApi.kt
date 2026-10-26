package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.rpc.models.AgentStateValue
import kotlinx.io.files.Path
import kotlin.time.Duration

/** Geometry and widgets only; the common composition owns control admission and commands. */
public interface AgentStatusRenderer {
    @Composable public fun Frame(regular: @Composable () -> Unit, settings: @Composable () -> Unit)
    @Composable public fun Text(value: String)
    @Composable public fun Action(label: String, enabled: Boolean, onClick: () -> Unit)
    @Composable public fun Configuration(viewModel: RuntimeConfigurationViewModel)
    @Composable public fun Directory(directory: Path, onBrowse: () -> Unit)
    @Composable public fun Settings(onOpen: () -> Unit)
}

/** Borrows this exact Agent; unmounting never closes it or its configuration child. */
@Composable
public expect fun AgentRuntimeStatusContent(
    viewModel: AgentViewModel,
    state: AgentStateValue,
    running: Boolean,
    settings: KodexAgentSettings,
    tokenCount: Long?,
    renderer: AgentStatusRenderer,
    onBrowseWorkingDirectory: () -> Unit,
    onOpenSettings: () -> Unit,
): Unit

public expect fun compactVisible(running: Boolean): Boolean

public interface AgentHistorySeparatorRenderer {
    @Composable
    public fun Separator(label: String?, showScrollToLatest: Boolean, onScrollToLatest: () -> Unit)
}

@Composable
public expect fun AgentHistorySeparatorContent(
    liveDuration: Duration?,
    showScrollToLatest: Boolean,
    onScrollToLatest: () -> Unit,
    renderer: AgentHistorySeparatorRenderer,
): Unit

public expect fun liveTurnDurationLabel(duration: Duration): String
public expect fun Duration.roundToSeconds(): Duration
