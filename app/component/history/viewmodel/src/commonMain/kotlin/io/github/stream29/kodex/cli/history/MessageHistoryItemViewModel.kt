package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant

internal class MessageHistoryItemViewModelImpl(
    override val index: Int,
    private val descriptor: HistoryItemDescriptor,
    private val context: HistoryItemLoadContext,
) : MessageHistoryItemViewModel, ReleasableHistoryItem {
    private val mutableState: MutableStateFlow<MessageHistoryItemState> = MutableStateFlow(
        try {
            val message = descriptor.event as? AgentCommit.ConversationMessage
                ?: error("History item $index is not a conversation message.")
            MessageHistoryItemState.Ready(
                event = message,
                elapsed = descriptor.elapsed,
                turnDuration = descriptor.turnDuration,
            )
        } catch (failure: Throwable) {
            context.logFailure(descriptor, failure)
            MessageHistoryItemState.Failed
        },
    )

    override val state: StateFlow<MessageHistoryItemState> = mutableState.asStateFlow()

    override suspend fun readTimestamp(): Instant? {
        check(context.isCurrent()) { "History generation is no longer current." }
        return descriptor.createdAt.takeIf { it != Instant.DISTANT_PAST }
    }

    override fun release() = Unit
}
