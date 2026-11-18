package io.github.stream29.kodex.agenteventbus

import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse

/**
 * Backend-global event delivery bus enumerating all external wakeup paths into an Agent.
 *
 * Implementations hold [KodexSessionRepository], watch active Sessions for `PendingAwait`
 * background terminal completions, and route every submitted event through
 * [KodexSessionRepository.openAgent] (activating unloaded Agents and renewing residency TTL) into
 * `AgentRuntime.submit*`:
 * - [submitUserMessage] -> `StableUserMessage`
 * - [submitAskUserAnswer] -> atomic `AskUserSnapshot` + `StableAskUserEvent`
 * - [submitScheduleTrigger] -> `StableScheduleEvent`
 * - [submitSubagentCompletion] -> `StableSubagentCompletedEvent`
 * - watched terminal exits -> `StableTerminalExitedEvent`
 *
 * Turn state inspection, background turn startup, and `stopEvents` emission remain owned by
 * `AgentRuntime` itself rather than duplicated in [AgentEventBus].
 */
public interface AgentEventBus {
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
    public suspend fun recordSubagentCreation(parent: KodexAgentSession, childAgentId: Int)

    /** Queues a completion notification for the child’s direct parent through normal steer. */
    public suspend fun submitSubagentCompletion(parentAgentId: Int, childAgentId: Int)

    /**
     * Records first-time [response] for [askId] in [agentId]'s `AskUserSnapshot` timeline and
     * atomically queues the corresponding `StableAskUserEvent` into `SteerSnapshot`, waking
     * [agentId] when `runningTurn == null`.
     *
     * @throws IllegalArgumentException if [askId] does not exist in [agentId]'s current `AskUserSnapshot`.
     * @throws IllegalStateException if [askId] has already been answered.
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
}

/**
 * Creates an [AgentEventBus] backed by [repository] and attaches watched background terminal
 * exit observation onto [KodexSessionRepository.openSessions] in [repository]'s scope.
 */
public expect fun AgentEventBus(
    repository: KodexSessionRepository,
): AgentEventBus
