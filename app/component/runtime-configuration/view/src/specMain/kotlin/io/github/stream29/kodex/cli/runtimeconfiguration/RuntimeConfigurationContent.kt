package io.github.stream29.kodex.cli.runtimeconfiguration

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationState
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.openai.*

/** Renderer presentation only; keys preserve model/effort/tier identity, not a second writable selection. */
public sealed interface RuntimeMenuEntry {
    public val key: Any
    public val label: String
    public val selected: Boolean

    /** Children are evaluated only for an opened level, avoiding a catalog-wide Cartesian product. */
    public data class Branch(
        override val key: Any, override val label: String, override val selected: Boolean,
        public val initialChildKey: Any,
        public val children: () -> List<RuntimeMenuEntry>,
    ) : RuntimeMenuEntry

    /** Only a leaf admits a command; branch navigation and dismissal never write. */
    public data class Command(
        override val key: Any, override val label: String, override val selected: Boolean,
        public val onSelect: () -> Unit,
    ) : RuntimeMenuEntry
}

public data class RuntimeConfigurationMenu(
    public val modelLabel: String,
    public val models: List<RuntimeMenuEntry>,
    public val modeLabel: String,
    public val modes: List<RuntimeMenuEntry>,
)

public interface RuntimeConfigurationRenderer {
    @Composable
    public fun Fields(menu: RuntimeConfigurationMenu, enabled: Boolean)
}

/** Borrows the exact child; composition owns command waits, not the child's observation lifetime. */
@Composable
public expect fun RuntimeConfigurationContent(
    viewModel: RuntimeConfigurationViewModel,
    renderer: RuntimeConfigurationRenderer,
    enabled: Boolean = true,
): Unit

/** Shared callback wiring for hosts whose triggers and menus must be mounted separately. */
@Composable
public expect fun RuntimeConfigurationMenuContent(
    viewModel: RuntimeConfigurationViewModel,
    content: @Composable (RuntimeConfigurationMenu) -> Unit,
): Unit

/** Captures this displayed snapshot and explicit tuples; closed state supplies no menu. */
public expect fun runtimeConfigurationMenu(
    state: RuntimeConfigurationState,
    onConfigurationSelected: (OpenAiModelId, ReasoningEffort, ServiceTier) -> Unit,
    onRequestUserInputModeSelected: (RequestUserInputMode) -> Unit,
): RuntimeConfigurationMenu?

/** Exact trigger text, also shared with Native host measurements. Only Default omits its suffix. */
public expect fun runtimeConfigurationLabel(model: OpenAiModelId, reasoning: ReasoningEffort, tier: ServiceTier): String

public expect fun runtimeRequestUserInputModeLabel(mode: RequestUserInputMode): String
