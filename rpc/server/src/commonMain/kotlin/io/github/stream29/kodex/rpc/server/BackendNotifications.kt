@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.rpc.models.Notification
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

public actual class BackendNotifications actual constructor() {
    private val events = MutableSharedFlow<Notification>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    public actual val flow: Flow<Notification> = events.asSharedFlow()
    internal val subscriptionCount get() = events.subscriptionCount

    internal fun publish(notification: Notification) {
        events.tryEmit(notification)
    }

    internal suspend fun stopped(index: Int, runtime: AgentRuntime, before: Int) {
        when (val state = runtime.state.value) {
            KodexAgentStateValue.AssistantMessage -> {
                val message = runtime.storage.getLastCommitsIn(
                    (before + 1)..runtime.latestIndex.value,
                    setOf(
                        AgentCommitType.AssistantMessage,
                        AgentCommitType.AssistantMessageCommentary,
                        AgentCommitType.AssistantMessageFinal,
                    ),
                    limit = 1,
                ).lastOrNull()?.commit as? StableAssistantMessage
                if (message != null) publish(Notification.Stop.AssistantMessage(index, message))
            }
            is KodexAgentStateValue.ToolPending -> {
                val questions = state.events.filterIsInstance<PendingRequestUserInputToolEvent>()
                val suggestions = state.events.filterIsInstance<PendingSuggestSubagentTaskToolEvent>()
                if (questions.isNotEmpty()) publish(Notification.Stop.RequestUserInput(index, questions))
                if (suggestions.isNotEmpty()) publish(Notification.Stop.SuggestSubagent(index, suggestions))
            }
            else -> Unit
        }
    }
}
