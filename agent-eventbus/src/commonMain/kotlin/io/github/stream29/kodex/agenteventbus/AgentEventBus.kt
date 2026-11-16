@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.agenteventbus

import io.github.stream29.kodex.agentruntime.contract.submitUserMessage
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableScheduleEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTerminalExitedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwaitEventToolEvent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecProcessSession
import kotlinx.coroutines.CancellationException
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
        repository.open(agentId).runtime.submitUserMessage(content)
    }

    override suspend fun submitAskUserAnswer(
        agentId: Int,
        askId: Int,
        response: RequestUserInputResponse,
    ) {
        repository.open(agentId).runtime.submitAskUserAnswer(askId, response)
    }

    override suspend fun submitScheduleTrigger(
        agentId: Int,
        scheduleId: Long,
        description: String,
    ) {
        repository.open(agentId).runtime.submit(
            listOf(
                StableScheduleEvent(
                    scheduleId = scheduleId,
                    description = description,
                ),
            ),
        )
    }

    private suspend fun submitTerminalExited(
        agentId: Int,
        sessionId: Int,
        name: String?,
        command: String,
        exitCode: Int,
    ) {
        repository.open(agentId).runtime.submit(
            listOf(
                StableTerminalExitedEvent(
                    sessionId = sessionId,
                    name = name,
                    command = command,
                    exitCode = exitCode,
                ),
            ),
        )
    }

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
                            observeSessionWatchedTerminals(agentId, session)
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

    private suspend fun observeSessionWatchedTerminals(
        agentId: Int,
        session: KodexAgentSession,
    ) {
        val dispatchedKeys = mutableSetOf<Pair<String, Int>>()
        val runtime = session.runtime

        combine(
            runtime.state,
            runtime.runningTurn,
            runtime.unifiedExecToolClient.activeSessions,
        ) { stateValue, runningTurn, activeSessions ->
            val awaitEvents = (stateValue as? KodexAgentStateValue.ToolPending)
                ?.events
                ?.takeIf { events -> events.all { it is PendingAwaitEventToolEvent } }
                ?.filterIsInstance<PendingAwaitEventToolEvent>()
                .orEmpty()
            WatchedAwaitSnapshot(
                awaitEvents = awaitEvents,
                parked = runningTurn == null && awaitEvents.isNotEmpty(),
                activeSessions = activeSessions,
            )
        }.flatMapLatest { snapshot ->
            val activeCallIds = snapshot.awaitEvents.mapTo(mutableSetOf()) { it.callId }
            dispatchedKeys.retainAll { (callId, _) -> callId in activeCallIds }

            if (!snapshot.parked) {
                flowOf(emptyList())
            } else {
                val watchedIds = snapshot.awaitEvents
                    .flatMap { it.arguments.terminals }
                    .distinct()
                val watchedSessions = watchedIds.mapNotNull { id -> snapshot.activeSessions[id] }
                if (watchedSessions.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(
                        watchedSessions.map { proc ->
                            proc.exitCode.map { code ->
                                code?.let { ExitedWatchedTerminal(snapshot.awaitEvents, proc, it) }
                            }
                        },
                    ) { candidates ->
                        candidates.filterNotNull()
                    }
                }
            }
        }.collect { exitedList ->
            for (exited in exitedList) {
                val matchingCalls = exited.awaitEvents.filter { pending ->
                    exited.session.sessionId in pending.arguments.terminals
                }
                val newlyTriggered = matchingCalls.any { pending ->
                    dispatchedKeys.add(pending.callId to exited.session.sessionId)
                }
                if (newlyTriggered) {
                    try {
                        submitTerminalExited(
                            agentId = agentId,
                            sessionId = exited.session.sessionId,
                            name = exited.session.arguments.name,
                            command = exited.session.arguments.command,
                            exitCode = exited.exitCode,
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        // Ignore races when the session closes or deletes concurrently.
                    }
                }
            }
        }
    }
}

private data class WatchedAwaitSnapshot(
    val awaitEvents: List<PendingAwaitEventToolEvent>,
    val parked: Boolean,
    val activeSessions: Map<Int, UnifiedExecProcessSession>,
)

private data class ExitedWatchedTerminal(
    val awaitEvents: List<PendingAwaitEventToolEvent>,
    val session: UnifiedExecProcessSession,
    val exitCode: Int,
)
