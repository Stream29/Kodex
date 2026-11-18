package io.github.stream29.kodex.cli.app

import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.check
import com.github.ajalt.clikt.parameters.groups.MutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.switch
import com.github.ajalt.clikt.parameters.types.int
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ReasoningEffortSerializer
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

public class SessionCommand : KodexGroupCommand(
    "session", "List and manage independent Sessions.",
    SessionListCommand(), SessionSpawnCommand(),
    SessionRenameCommand(), SessionForkCommand(), SessionArchiveCommand(), SessionUnarchiveCommand(), SessionDeleteCommand(),
    isToolGroup = true,
)

public class AgentCommand : KodexGroupCommand(
    "agent", "Inspect and control a specific Agent.",
    AgentStatusCommand(), AgentSubmitCommand(), AgentResumeCommand(), AgentStopCommand(),
    AgentCompactCommand(), AgentClearPendingCommand(),
    AgentSqlCommand(),
    isToolGroup = true,
)

/** Session/Agent operations report a single JSON receipt after parsing succeeds. */
public sealed class SessionAgentOperation(name: String, summary: String) : KodexToolLeafCommand(name, summary)

public class SessionListCommand : SessionAgentOperation("list", "List the backend's Session catalog.") {
    public val includeArchived: Boolean by option("--include-archived", help = "Include archived Sessions.").flag()
}

public sealed class ExplicitAgentOperation(name: String, summary: String) : SessionAgentOperation(name, summary) {
    public val agentId: Int by option("--agent", metavar = "ID", help = "Required target Agent ID.")
        .int().required().check("Agent ID must be non-negative.") { it >= 0 }
}

public class AgentStatusCommand : ExplicitAgentOperation(
    "status", "Read UTC time, Agent state, and remaining context-window budget.",
)

public class SessionSpawnCommand : SessionAgentOperation("spawn", "Create an independent Session, optionally submitting its first message.") {
    public val cwd: String? by option("--cwd", metavar = "DIR", help = "Initial working directory, relative to this CLI process if needed.")
    public val model: OpenAiModelId? by option("--model", metavar = "MODEL", help = "Override the initial model.")
        .convert { require(it.isNotBlank()) { "Model must not be blank." }; OpenAiModelId(it) }
    public val reasoningEffort: ReasoningEffort? by option("--reasoning-effort", metavar = "EFFORT", help = "Override the initial reasoning effort.")
        .convert {
            require(it.isNotBlank()) { "Reasoning effort must not be blank." }
            CliToolJson.decodeFromJsonElement(ReasoningEffortSerializer, JsonPrimitive(it))
        }
    public val serviceTier: ServiceTier? by option("--service-tier", metavar = "TIER", help = "Override the initial service tier.")
        .convert { ServiceTier.entries.firstOrNull { tier -> tier.requestValue == it } ?: error("Invalid service tier.") }
    public val requestUserInputMode: RequestUserInputMode? by option(
        "--request-user-input-mode", metavar = "MODE", help = "Override the initial question mode: ask_user or no_question.",
    ).convert { CliToolJson.decodeFromJsonElement(RequestUserInputMode.serializer(), JsonPrimitive(it)) }
    public val message: CliPayloadInput<String>? by textOperationInput("--message", "Initial message text.")

    override fun validate() {
        if (cwd?.isBlank() == true) throw UsageError("Working directory must not be blank.", paramName = "--cwd")
    }
}

public class AgentSubmitCommand : ExplicitAgentOperation("submit", "Submit one text message; return when the backend accepts it.") {
    public val message: CliPayloadInput<String> by textOperationInput("--message", "Message text.").required()
}

public class AgentResumeCommand : ExplicitAgentOperation("resume", "Continue without a new message; return on admission.")

public class AgentStopCommand : ExplicitAgentOperation("stop", "Request cancellation of only the currently running turn.")

public class AgentCompactCommand : ExplicitAgentOperation("compact", "Compact the Agent's context and return its committed index.")

public class AgentClearPendingCommand : ExplicitAgentOperation(
    "clear-pending", "Resolve pending tool calls as interrupted without clearing messages or resuming.",
)

public sealed class ExplicitSessionOperation(name: String, summary: String) : SessionAgentOperation(name, summary) {
    public val sessionId: Int by option("--session", metavar = "ID", help = "Required target Session ID.")
        .int().required().check("Session ID must be non-negative.") { it >= 0 }
}

public class SessionRenameCommand : ExplicitSessionOperation("rename", "Update only the Session's title.") {
    public val name: String by option("--name", metavar = "TEXT", help = "New Session title.")
        .convert { it.trim().also { name -> require(name.isNotEmpty()) { "Session title must not be blank." } } }.required()
}

public class SessionForkCommand : ExplicitSessionOperation("fork", "Fork the complete Session as a new independent Session.")

public class SessionArchiveCommand : ExplicitSessionOperation("archive", "Archive the Session without stopping it.")

public class SessionUnarchiveCommand : ExplicitSessionOperation("unarchive", "Remove the Session's archived flag.")

public class SessionDeleteCommand : ExplicitSessionOperation("delete", "Close and permanently delete the specified Session.")

public class AskAnswerCommand : ExplicitAgentOperation("answer", "Submit a user-provided structured answer to an existing question.") {
    public val askId: Int by argument("ask-id", help = "1-based local ask ID.").int().check("Ask ID must be positive.") { it > 0 }
    public val response: CliPayloadInput<String> by textOperationInput("--json", "RequestUserInputResponse JSON object.").required()
}

private fun ParameterHolder.textOperationInput(
    optionName: String, optionHelp: String,
): MutuallyExclusiveOptions<CliPayloadInput<String>, CliPayloadInput<String>?> = mutuallyExclusiveOptions(
    option(optionName, help = optionHelp).convert {
        require(it.isNotEmpty()) { "Input must not be empty." }
        CliPayloadInput.Inline(it)
    },
    option(help = "Read raw UTF-8 from standard input.").switch("--stdin" to CliPayloadInput.Stdin),
).single()

@Serializable
public data class CliSessionCatalogOutput(public val sessions: List<CliSessionCatalogRow>)

@Serializable
public data class CliSessionCatalogRow(
    @SerialName("session_id") public val sessionId: Int,
    @SerialName("root_agent_id") public val rootAgentId: Int,
    @SerialName("thread_name") public val threadName: String?,
    public val archived: Boolean,
    public val running: Boolean,
    @SerialName("is_active") public val isActive: Boolean,
    @SerialName("created_at") public val createdAt: String?,
    @SerialName("updated_at") public val updatedAt: String?,
)

@Serializable
public data class CliOperationEnvelope(
    public val ok: Boolean,
    public val result: JsonElement? = null,
    public val error: CliOperationFailure? = null,
)

@Serializable
public data class CliOperationFailure(
    public val code: String,
    public val message: String,
    public val phase: String,
    public val outcome: CliOperationOutcome,
    @SerialName("session_id") public val sessionId: Int? = null,
    @SerialName("agent_id") public val agentId: Int? = null,
)

@Serializable
public enum class CliOperationOutcome {
    @SerialName("rejected") Rejected,
    @SerialName("failed") Failed,
    @SerialName("unknown") Unknown,
}
