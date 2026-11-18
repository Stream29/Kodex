@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.agenteventbus

import io.github.stream29.kodex.agentruntime.contract.submitUserMessage
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableScheduleEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCreatedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwait
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecProcessSession
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
): AgentEventBus =
    RepositoryAgentEventBus(repository).also { bus ->
        bus.attachWatchedTerminalExitObserver()
    }

private class RepositoryAgentEventBus(
    private val repository: KodexSessionRepository,
) : AgentEventBus {
    override suspend fun submitUserMessage(
        agentId: Int,
        content: List<ContentItem>,
    ) {
        if (content.isEmpty()) return
        submitUserMessage(requireAgent(agentId), content)
    }

    override suspend fun submitUserMessage(session: KodexAgentSession, content: List<ContentItem>) {
        if (content.isNotEmpty()) session.runtime.submitUserMessage(content)
    }

    override suspend fun recordSubagentCreation(parent: KodexAgentSession, childAgentId: Int) {
        parent.runtime.modify { it.commit(StableSubagentCreatedEvent(childAgentId)) }
    }

    override suspend fun submitAskUserAnswer(
        agentId: Int,
        askId: Int,
        response: RequestUserInputResponse,
    ) {
        requireAgent(agentId).runtime.submitAskUserAnswer(askId, response)
    }

    override suspend fun submitScheduleTrigger(
        agentId: Int,
        scheduleId: Long,
        description: String,
    ) {
        requireAgent(agentId).runtime.submit(
            listOf(
                StableScheduleEvent(
                    scheduleId = scheduleId,
                    description = description,
                ),
            ),
        )
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
