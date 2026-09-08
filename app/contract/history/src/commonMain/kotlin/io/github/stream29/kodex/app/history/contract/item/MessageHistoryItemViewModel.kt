package io.github.stream29.kodex.app.history.contract.item

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Instant

/** A stored user, assistant, developer, or inter-Agent message. */
public interface MessageHistoryItemViewModel : HistoryItemViewModel {
    public val index: Int
    public val state: StateFlow<MessageHistoryItemState>

    /**
     * Reads this message's exact timestamp without changing its content state.
     * @return null when no timestamp exists at this message's index.
     */
    public suspend fun readTimestamp(): Instant?
}

/**
 * Complete state machine for a message row.
 *
 * Normal transition: [Loading] -> [Ready]. Loading failure transitions to [Failed]. Leaving the
 * measured viewport or switching sessions does not change this state.
 */
public sealed interface MessageHistoryItemState {
    /** The complete message presentation is not available yet; the View renders one empty row. */
    public data class Loading(
        public val loadingJob: Job,
    ) : MessageHistoryItemState

    /** The View can render the complete message without another storage read. */
    public data class Ready(
        public val event: StableIndexEvent.Steerable,
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
