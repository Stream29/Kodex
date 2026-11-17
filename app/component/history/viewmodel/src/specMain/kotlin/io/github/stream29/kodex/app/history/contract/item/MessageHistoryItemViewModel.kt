package io.github.stream29.kodex.app.history.contract.item

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Instant

/** A stored user, assistant, developer, or inter-Agent message. */
public interface MessageHistoryItemViewModel : WorkGroupChildHistoryItemViewModel {
    public override val index: Int
    public val state: StateFlow<MessageHistoryItemState>

    /**
     * Reads this message's exact timestamp without changing its content state.
     * @return null when no timestamp exists at this message's index.
     * @throws IllegalStateException if this item's generation is no longer current before or
     * after reading (including owner close). No timestamp from the replacement is returned.
     * @throws kotlinx.coroutines.CancellationException if the caller or item owner is cancelled.
     * @throws Exception if the captured storage timestamp read fails; failure is not absence.
     */
    public suspend fun readTimestamp(): Instant?
}

/** Complete state machine for a message row. */
public sealed interface MessageHistoryItemState {
    /**
     * The View can render the complete message without another storage read.
     * @throws IllegalArgumentException if [elapsed] or non-null [turnDuration] is negative or infinite.
     */
    public data class Ready(
        public val event: AgentCommit.Steerable,
        public val elapsed: Duration,
        /** Complete turn duration rendered below a final assistant message. */
        public val turnDuration: Duration? = null,
    ) : MessageHistoryItemState {
        init {
            requireHistoryItemElapsed(elapsed)
            turnDuration?.let(::requireHistoryItemElapsed)
        }
    }

    /** The message could not be loaded; the View renders the red error fallback. */
    public data object Failed : MessageHistoryItemState
}
