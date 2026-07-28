package io.github.stream29.codex.lite.tool.multiagent

import io.github.stream29.codex.lite.openai.ResponsesApiTool

/** Fixed Multi-agent V2 tool surface. */
public object MultiAgentTools {
    public const val SpawnAgentName: String = "spawn_agent"
    public const val SendMessageName: String = "send_message"
    public const val FollowupTaskName: String = "followup_task"
    public const val WaitAgentName: String = "wait_agent"
    public const val InterruptAgentName: String = "interrupt_agent"
    public const val ListAgentsName: String = "list_agents"

    public const val DefaultWaitTimeoutMillis: Long = 30_000
    public const val MinWaitTimeoutMillis: Long = 10_000
    public const val MaxWaitTimeoutMillis: Long = 3_600_000

    public val spawnAgentSpec: ResponsesApiTool = ResponsesApiTool(
        name = SpawnAgentName,
        description = "Spawns an agent to work on a concrete, bounded task. The child receives its full canonical Agent path and can use the same Multi-agent tools as its parent. It inherits the current model by default; use fork_turns to select none, all, or a positive number of recent turns.",
        parameters = SpawnAgentParametersSchema,
        outputSchema = SpawnAgentOutputSchema,
    )

    public val sendMessageSpec: ResponsesApiTool = ResponsesApiTool(
        name = SendMessageName,
        description = "Send a message to an existing agent. The message will be delivered promptly. Does not trigger a new turn.",
        parameters = SendMessageParametersSchema,
    )

    public val followupTaskSpec: ResponsesApiTool = ResponsesApiTool(
        name = FollowupTaskName,
        description = "Send a follow-up task to an existing non-root target agent and trigger a turn if it is idle. If the target is already running, deliver the task after its current operation reaches a stable boundary.",
        parameters = FollowupTaskParametersSchema,
    )

    public val waitAgentSpec: ResponsesApiTool = ResponsesApiTool(
        name = WaitAgentName,
        description = "Wait for a mailbox update from any live agent, including queued messages and final-status notifications. Does not return final content; it returns an activity or timeout summary.",
        parameters = WaitAgentParametersSchema,
        outputSchema = WaitAgentOutputSchema,
    )

    public val interruptAgentSpec: ResponsesApiTool = ResponsesApiTool(
        name = InterruptAgentName,
        description = "Interrupt an agent's current turn, if any, and return its previous status. The agent remains available for messages and follow-up tasks.",
        parameters = InterruptAgentParametersSchema,
        outputSchema = InterruptAgentOutputSchema,
    )

    public val listAgentsSpec: ResponsesApiTool = ResponsesApiTool(
        name = ListAgentsName,
        description = "List live agents in the current root thread tree. Optionally filter by task-path prefix.",
        parameters = ListAgentsParametersSchema,
        outputSchema = ListAgentsOutputSchema,
    )

    public val specs: List<ResponsesApiTool> = listOf(
        spawnAgentSpec,
        sendMessageSpec,
        followupTaskSpec,
        waitAgentSpec,
        interruptAgentSpec,
        listAgentsSpec,
    )
}
