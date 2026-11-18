package io.github.stream29.kodex.cli.app

import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

public class SessionCommand : KodexGroupCommand(
    "session", "List and manage independent Sessions.",
    SessionListCommand(),
    isToolGroup = true,
)

public class AgentCommand : KodexGroupCommand(
    "agent", "Inspect and control a specific Agent.",
    AgentStatusCommand(),
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
