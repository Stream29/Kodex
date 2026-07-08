package io.github.stream29.codex.lite.agentstorage.cleanmodels

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean event item that can be appended to clean history.
 */
@Serializable
public sealed interface StableCleanEvent

/**
 * Recomputable clean event item that is visible only while raw state is pending.
 */
@Serializable
public sealed interface UnstableCleanEvent {
    /**
     * Tool call waiting for a matched tool result.
     *
     * @property name Tool name shown to users.
     * @property kind Tool-call protocol shape used by the model.
     * @property namespace Nullable because plain function tools are not
     * namespaced; `null` means route or display by [name] only.
     * @property input Raw model-provided tool input. For function tools this is
     * JSON text; for custom tools this is freeform text.
     */
    @Serializable
    @SerialName("pending_tool_call")
    public data class PendingToolCall(
        public val name: String,
        public val kind: PendingToolCallKind,
        public val namespace: String? = null,
        public val input: String,
    ) : UnstableCleanEvent

    /**
     * Context compaction request waiting for the compaction result.
     */
    @Serializable
    @SerialName("pending_context_compaction")
    public data object PendingContextCompaction : UnstableCleanEvent
}

/**
 * Protocol shape for an unstable pending tool call.
 */
@Serializable
public enum class PendingToolCallKind {
    @SerialName("function")
    Function,

    @SerialName("custom")
    Custom,
}

/**
 * Recomputable clean projection tail.
 *
 * [StableCleanEvent] items belong in stable history after projection confirms
 * they no longer depend on future raw/tool state. This snapshot contains only
 * pending clean events that must be replaced or cleared when the pending raw
 * state resolves.
 *
 * @property items Current pending clean event suffix.
 */
@Serializable
@SerialName("unstable_clean_tail")
public data class UnstableCleanTail(
    public val items: List<UnstableCleanEvent>,
)
