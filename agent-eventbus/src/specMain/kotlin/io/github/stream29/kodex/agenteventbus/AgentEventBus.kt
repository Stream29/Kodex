package io.github.stream29.kodex.agenteventbus

import io.github.stream29.kodex.agentruntime.contract.PendingAwaitDeadline
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCreatedEvent
import io.github.stream29.kodex.agentstorage.contract.ext.AskUserAlreadyAnswered
import io.github.stream29.kodex.agentstorage.contract.ext.AskUserEntryNotFound
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.utils.mq.MqBroker

/**
 * Backend-global event delivery bus enumerating all external wakeup paths into an Agent.
 *
 * ID-addressed submissions go through [BackendMqQueues.AgentInbox] and await a Direct Reply-To
 * confirmation. Consumers compete for deliveries and ordinarily resolve the target with
 * [KodexSessionRepository.openAgent], activating unloaded Agents and renewing residency TTL.
 * Deadline submissions open only a matching await. Captured-session operations and watched
 * terminal exits stay local.
 *
 * Cancelling a submission stops waiting for its confirmation, not processing an already queued
 * message. Consumer cancellation can cause redelivery of unsettled work. Turn execution and stop
 * events remain owned by `AgentRuntime`.
 */
public interface AgentEventBus {
    /** Broker shared by the inbox and backend broadcast publishers and subscribers. */
    public val broker: MqBroker

    /**
     * Delivers a user message (`StableUserMessage`) to [agentId].
     *
     * Empty [content] is a no-op. Does not include session-title generation.
     */
    public suspend fun submitUserMessage(
        agentId: Int,
        content: List<ContentItem>,
    )

    /** Delivers to a previously verified owner without re-resolving a reusable numeric id. */
    public suspend fun submitUserMessage(session: KodexAgentSession, content: List<ContentItem>)

    /** Records creation on the captured calling parent without waking or steering it. */
    public suspend fun recordSubagentCreation(parent: KodexAgentSession, event: StableSubagentCreatedEvent)

    /** Queues a completion notification for the child’s direct parent through normal steer. */
    public suspend fun submitSubagentCompletion(parentAgentId: Int, childAgentId: Int)

    /**
     * Records first-time [response] for [askId] in [agentId]'s `AskUserSnapshot` timeline and
     * atomically queues the corresponding `StableAskUserEvent` into `SteerSnapshot`, waking
     * [agentId] when `runningTurn == null`.
     *
     * @throws AskUserEntryNotFound if [askId] does not exist in [agentId]'s current `AskUserSnapshot`.
     * @throws AskUserAlreadyAnswered if [askId] has already been answered.
     */
    public suspend fun submitAskUserAnswer(
        agentId: Int,
        askId: Int,
        response: RequestUserInputResponse,
    )

    /**
     * Delivers a fired schedule trigger (`StableScheduleEvent`) to [agentId].
     */
    public suspend fun submitScheduleTrigger(
        agentId: Int,
        scheduleId: Long,
        description: String,
    )

    /**
     * Waits for the runtime's check of [expected] to finish. A stale deadline is acknowledged
     * without waking the runtime or reopening an evicted Agent.
     */
    public suspend fun submitAwaitDeadline(agentId: Int, expected: PendingAwaitDeadline)
}

/**
 * Creates an [AgentEventBus] using [broker] and starts inbox consumption and watched terminal
 * exit observation in [repository]'s scope.
 */
public expect fun AgentEventBus(
    repository: KodexSessionRepository,
    broker: MqBroker,
): AgentEventBus
