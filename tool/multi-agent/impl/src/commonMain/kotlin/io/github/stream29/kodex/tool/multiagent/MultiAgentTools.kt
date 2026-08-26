package io.github.stream29.kodex.tool.multiagent

import io.github.stream29.kodex.openai.ResponsesApiTool

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
        description = SpawnAgentDescription,
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
        description = "Send a follow-up task to an existing non-root target agent and trigger a turn if it is idle. If the target is already running, deliver the task promptly at message boundaries while sampling, or after the pending tool call completes.",
        parameters = FollowupTaskParametersSchema,
    )

    public val waitAgentSpec: ResponsesApiTool = ResponsesApiTool(
        name = WaitAgentName,
        description = "Wait for a pending steering message from any live agent, including queued messages and final-status notifications. Does not return final content; it returns an activity or timeout summary.",
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

private val SpawnAgentDescription: String = """
    Spawns an agent to work on the specified task. If your current task is `/root/task1` and you spawn_agent with task_name "task_3" the agent will have canonical task name `/root/task1/task_3`.
    The spawned agent will have the same tools as you and the ability to spawn its own subagents.
    Spawned agents inherit your current model by default. Omit `model` to use that preferred default; set `model` only when an explicit override is needed.
    Only call this tool for a concrete, bounded subtask that can run independently alongside useful local work; otherwise continue locally.
    It will be able to send you and other running agents messages, and its final answer will be provided to you when it finishes.
    The new agent's canonical task name will be provided to it along with the message.

    The spawned agent receives the current active context window. It does not receive completed history that has already been compacted away.
""".trimIndent()
