package io.github.stream29.kodex.agentstate.impl

import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType

/** Backend-only recovery state at a fork's first retained compaction. Never an AgentCommit. */
public data class ForkContextRecovery(
    public val checkpointIndex: Int,
    public val phase: ForkContextPhase,
    public val startsNewTurn: Boolean,
)

public enum class ForkContextPhase(public val state: KodexAgentStateValue) {
    Empty(KodexAgentStateValue.Empty),
    UserMessage(KodexAgentStateValue.UserMessage),
    AssistantMessage(KodexAgentStateValue.AssistantMessage),
    ToolCompleted(KodexAgentStateValue.ToolCompleted),
}

private val PhaseByCommitType: Map<AgentCommitType, ForkContextPhase> = buildMap {
    for (type in listOf(
        AgentCommitType.UserMessage, AgentCommitType.AgentMessage, AgentCommitType.EventAskUser,
        AgentCommitType.EventSchedule, AgentCommitType.EventSubagentCompleted, AgentCommitType.EventTerminalExited,
    )) put(type, ForkContextPhase.UserMessage)
    for (type in listOf(
        AgentCommitType.AssistantMessage, AgentCommitType.AssistantMessageCommentary, AgentCommitType.AssistantMessageFinal,
    )) put(type, ForkContextPhase.AssistantMessage)
    for (type in listOf(
        AgentCommitType.AwaitEventToolEvent, AgentCommitType.PlanUpdate, AgentCommitType.CommandExecutionToolEvent,
        AgentCommitType.PatchToolEvent, AgentCommitType.ReadMediaToolEvent, AgentCommitType.InvalidToolCall,
        AgentCommitType.ServerToolSearch, AgentCommitType.HostedWebSearch, AgentCommitType.HostedImageGeneration,
        AgentCommitType.JsonToolEvent, AgentCommitType.TextToolEvent, AgentCommitType.CustomToolEvent,
    )) put(type, ForkContextPhase.ToolCompleted)
}

/** Commit types used to recover the latest stable phase, independently of pending work. */
public val StablePhaseTransitionTypes: Set<AgentCommitType> = PhaseByCommitType.keys

public fun stablePhaseAt(
    lastTransition: Pair<Int, AgentCommitType>?,
    recovery: ForkContextRecovery?,
): ForkContextPhase =
    if (recovery != null && recovery.checkpointIndex > (lastTransition?.first ?: -1)) recovery.phase
    else lastTransition?.second?.let(PhaseByCommitType::get) ?: ForkContextPhase.Empty

public fun startsNewTurnAt(
    lastBoundary: Pair<Int, AgentCommitType>?,
    recovery: ForkContextRecovery?,
): Boolean =
    if (recovery != null && recovery.checkpointIndex > (lastBoundary?.first ?: -1)) recovery.startsNewTurn
    else lastBoundary?.second == AgentCommitType.AssistantMessage || lastBoundary?.second == AgentCommitType.AssistantMessageFinal
