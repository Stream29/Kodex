@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.agenteventbus

import io.github.stream29.kodex.agentruntime.contract.PendingAwaitDeadline
import io.github.stream29.kodex.agentruntime.contract.submitUserMessage
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableScheduleEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCompletedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCreatedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwait
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecProcessSession
import io.github.stream29.kodex.utils.mq.MqBroker
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

public actual fun AgentEventBus(
    repository: KodexSessionRepository,
    broker: MqBroker,
): AgentEventBus =
    RepositoryAgentEventBus(repository, broker).also { bus ->
        bus.attachInboxConsumer()
        bus.attachWatchedTerminalExitObserver()
    }

private class RepositoryAgentEventBus(
    private val repository: KodexSessionRepository,
    override val broker: MqBroker,
) : AgentEventBus {
    private val inbox = AgentInbox(broker)

    fun attachInboxConsumer() {
        repository.launch(start = CoroutineStart.UNDISPATCHED) {
            inbox.consume(::handleInboxMessage)
        }
    }

    override suspend fun submitUserMessage(
        agentId: Int,
        content: List<ContentItem>,
    ) {
        if (content.isEmpty()) return
        inbox.submit(AgentInboxMessage.UserMessage(agentId, content))
    }

    override suspend fun submitUserMessage(session: KodexAgentSession, content: List<ContentItem>) {
        if (content.isNotEmpty()) session.runtime.submitUserMessage(content)
    }

    override suspend fun recordSubagentCreation(parent: KodexAgentSession, event: StableSubagentCreatedEvent) {
        parent.runtime.modify { it.commit(event) }
    }

    override suspend fun submitSubagentCompletion(parentAgentId: Int, childAgentId: Int) {
        inbox.submit(AgentInboxMessage.SubagentCompletion(parentAgentId, childAgentId))
    }

    override suspend fun submitAskUserAnswer(
        agentId: Int,
        askId: Int,
        response: RequestUserInputResponse,
    ) {
        inbox.submit(AgentInboxMessage.AskUserAnswer(agentId, askId, response))
    }

    override suspend fun submitScheduleTrigger(
        agentId: Int,
        scheduleId: Long,
        description: String,
    ) {
        inbox.submit(AgentInboxMessage.ScheduleTrigger(agentId, scheduleId, description))
    }

    override suspend fun submitAwaitDeadline(agentId: Int, expected: PendingAwaitDeadline) {
        inbox.submit(AgentInboxMessage.AwaitDeadlineTrigger(
            agentId, expected.snapshotIndex, expected.callId, expected.timeoutAt,
        ))
    }

    private suspend fun handleInboxMessage(message: AgentInboxMessage) {
        val agentId = message.targetAgentId
        when (message) {
            is AgentInboxMessage.UserMessage -> {
                if (message.content.isNotEmpty()) submitUserMessage(requireAgent(agentId), message.content)
            }
            is AgentInboxMessage.AskUserAnswer ->
                requireAgent(agentId).runtime.submitAskUserAnswer(message.askId, message.response)
            is AgentInboxMessage.ScheduleTrigger ->
                requireAgent(agentId).runtime.submit(listOf(StableScheduleEvent(message.scheduleId, message.description)))
            is AgentInboxMessage.SubagentCompletion ->
                requireAgent(agentId).runtime.submit(listOf(StableSubagentCompletedEvent(message.childAgentId)))
            is AgentInboxMessage.AwaitDeadlineTrigger -> {
                val expected = PendingAwaitDeadline(message.snapshotIndex, message.callId, message.timeoutAt)
                repository.openForAwaitDeadline(agentId, expected)?.runtime?.requestAwaitDeadlineCheck(expected)?.join()
            }
        }
    }

    private suspend fun requireAgent(agentId: Int): KodexAgentSession =
        requireNotNull(repository.openAgent(agentId)) { "Agent $agentId does not exist." }

    fun attachWatchedTerminalExitObserver() {
        val watchers = mutableMapOf<Int, Pair<KodexAgentSession, Job>>()
        repository.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                repository.openSessions.collect { openByAgent ->
                    val removedIds = watchers.keys.filter { id -> openByAgent[id] !== watchers[id]?.first }
                    for (id in removedIds) {
                        watchers.remove(id)?.second?.cancel()
                    }
                    for ((agentId, session) in openByAgent) {
                        if (watchers[agentId]?.first === session) continue
                        val job = session.launch(start = CoroutineStart.UNDISPATCHED) {
                            observeSessionWatchedTerminals(session)
                        }
                        watchers[agentId] = session to job
                    }
                }
            } finally {
                watchers.values.forEach { (_, job) -> job.cancel() }
                watchers.clear()
            }
        }
    }

    private suspend fun observeSessionWatchedTerminals(session: KodexAgentSession) {
        val runtime = session.runtime

        combine(
            runtime.state,
            runtime.runningTurn,
            runtime.unifiedExecToolClient.activeSessions,
        ) { stateValue, runningTurn, activeSessions ->
            val awaitEvents = (stateValue as? KodexAgentStateValue.ToolPending)
                ?.events
                ?.takeIf { events -> events.all { it is PendingAwait } }
                ?.filterIsInstance<PendingAwait>()
                .orEmpty()
            WatchedAwaitSnapshot(
                awaitEvents = awaitEvents,
                parked = runningTurn == null && awaitEvents.isNotEmpty(),
                activeSessions = activeSessions,
            )
        }.flatMapLatest { snapshot ->
            if (!snapshot.parked) {
                flowOf(null)
            } else {
                val watched = buildMap {
                    for (pending in snapshot.awaitEvents) {
                        for (id in pending.arguments.terminalIds) if (id !in this) put(id, pending)
                    }
                }
                val missing = watched.entries.firstOrNull { (id, _) -> id !in snapshot.activeSessions }
                if (missing != null) {
                    flowOf(WatchedTerminalCheck(missing.value, missing.key))
                } else if (watched.isEmpty()) {
                    flowOf(null)
                } else {
                    combine(
                        watched.map { (id, pending) ->
                            snapshot.activeSessions.getValue(id).exitCode.map { exitCode ->
                                exitCode?.let { WatchedTerminalCheck(pending, id) }
                            }
                        },
                    ) { candidates ->
                        candidates.firstOrNull { it != null }
                    }
                }
            }
        }.collect { check ->
            if (check != null) {
                runtime.requestAwaitTerminalCheck(check.pending, check.terminalId).join()
            }
        }
    }
}

private data class WatchedAwaitSnapshot(
    val awaitEvents: List<PendingAwait>,
    val parked: Boolean,
    val activeSessions: Map<Int, UnifiedExecProcessSession>,
)

private data class WatchedTerminalCheck(
    val pending: PendingAwait,
    val terminalId: Int,
)
