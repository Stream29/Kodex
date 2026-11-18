@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.kodex.cli.app

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.check
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.groups.MutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.default
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.switch
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.uint
import com.github.ajalt.clikt.parameters.types.ulong
import io.github.stream29.kodex.mcp.contract.McpCatalogTool
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.SearchQuery
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import kotlin.time.Instant
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/** Source of a structured JSON payload passed inline via option or streamed via `--stdin`. */
public sealed interface CliPayloadInput<out T> {
    public data class Inline<out T>(public val value: T) : CliPayloadInput<T>
    public data object Stdin : CliPayloadInput<Nothing>
}

/** Validated trigger specification for `kodex schedule create`. */
public sealed interface CliScheduleTrigger {
    public data class DelaySeconds(public val seconds: Long) : CliScheduleTrigger {
        init {
            require(seconds > 0L) { "Schedule delay seconds must be positive: $seconds" }
        }
    }

    public data class At(public val triggerAt: Instant) : CliScheduleTrigger

    public data class Cron(public val expression: String) : CliScheduleTrigger {
        init {
            require(expression.trim().split(Regex("\\s+")).size == 5) {
                "Cron expression must contain 5 space-separated fields (minute hour day-of-month month day-of-week)."
            }
        }
    }
}

private fun <T : Any> ParameterHolder.jsonPayloadOptions(
    serializer: KSerializer<T>,
    jsonHelp: String,
): MutuallyExclusiveOptions<CliPayloadInput<T>, CliPayloadInput<T>?> = mutuallyExclusiveOptions(
    option("--json", metavar = "JSON", help = jsonHelp)
        .convert { CliPayloadInput.Inline(CliToolJson.decodeFromString(serializer, it)) },
    option(help = "Read JSON payload from standard input.")
        .switch("--stdin" to CliPayloadInput.Stdin),
).single()

public class TerminalCommand : KodexGroupCommand(
    "terminal",
    "Inspect, write to, or terminate Agent background shell sessions.",
    TerminalListCommand(),
    TerminalReadCommand(),
    TerminalWriteCommand(),
    TerminalKillCommand(),
    isToolGroup = true,
)

public class TerminalListCommand : KodexAgentToolCommand(
    "list",
    "List background shell sessions for the target Agent.",
)

public class TerminalReadCommand : KodexAgentToolCommand(
    "read",
    "Non-destructively read buffered output from a shell session.",
) {
    public val sessionId: Int by argument("session-id", help = "Shell session ID.").int()
    public val offset: ULong by option("--offset", metavar = "BYTES", help = "Zero-based byte offset.").ulong().default(0uL)
    public val limit: UInt by option("--limit", metavar = "BYTES", help = "Maximum bytes to return.").uint().default(UInt.MAX_VALUE)
}

public class TerminalWriteCommand : KodexAgentToolCommand(
    "write",
    "Write UTF-8 text or stdin bytes to a running shell session.",
) {
    public val sessionId: Int by argument("session-id", help = "Running shell session ID.").int()
    public val chars: String? by argument("chars", help = "UTF-8 text to write (omit with --stdin or --eof).").optional()
    public val useStdin: Boolean by option("--stdin", help = "Read input bytes from standard input.").flag()
    public val closeInput: Boolean by option("--eof", help = "Close process stdin after writing.").flag()

    override fun validate() {
        if (chars != null && useStdin) throw UsageError("Cannot combine positional chars with --stdin.", paramName = "--stdin")
        if (chars == null && !useStdin && !closeInput) throw UsageError("Provide positional chars, --stdin, or --eof.")
    }
}

public class TerminalKillCommand : KodexAgentToolCommand(
    "kill",
    "Request termination of a background shell session.",
) {
    public val sessionId: Int by argument("session-id", help = "Shell session ID to terminate.").int()
}

public class AskCommand : KodexGroupCommand(
    "ask",
    "Create non-blocking structured user-input questions or inspect existing questions and answers.",
    AskCreateCommand(),
    AskListCommand(),
    AskGetCommand(),
    isToolGroup = true,
)

public class AskCreateCommand : KodexAgentToolCommand(
    "create",
    "Create a non-blocking user-input prompt in AskUserSnapshot.",
) {
    public val arguments: CliPayloadInput<RequestUserInputArgs> by jsonPayloadOptions(
        RequestUserInputArgs.serializer(),
        "RequestUserInputArgs JSON object ({\"questions\":[{\"id\":\"q1\",\"header\":\"Topic\",\"question\":\"...\"}]}).",
    ).required()
}

public class AskListCommand : KodexAgentToolCommand(
    "list",
    "List all AskUserSnapshot entries for the target Agent.",
)

public class AskGetCommand : KodexAgentToolCommand(
    "get",
    "Get one AskUserSnapshot entry by its local ask-id.",
) {
    public val askId: Int by argument("ask-id", help = "1-based local ask_id.").int().check("ask-id must be positive.") { it >= 1 }
}

public class ScheduleCommand : KodexGroupCommand(
    "schedule",
    "Create, list, or delete persisted one-shot and recurring cron schedules.",
    ScheduleCreateCommand(),
    ScheduleListCommand(),
    ScheduleDeleteCommand(),
    isToolGroup = true,
)

public class ScheduleCreateCommand : KodexAgentToolCommand(
    "create",
    "Create a persisted one-shot (--delay-seconds / --at) or recurring (--cron) schedule.",
) {
    public val description: String by option("--description", "-d", metavar = "TEXT", help = "Reminder description.").required()
    public val trigger: CliScheduleTrigger by mutuallyExclusiveOptions(
        option("--delay-seconds", metavar = "SECONDS", help = "One-shot delay in positive seconds.")
            .long().convert { CliScheduleTrigger.DelaySeconds(it) },
        option("--at", metavar = "INSTANT", help = "One-shot ISO-8601 trigger instant.")
            .convert { CliScheduleTrigger.At(Instant.parse(it)) },
        option("--cron", metavar = "EXPR", help = "Recurring 5-field UTC cron expression.")
            .convert { CliScheduleTrigger.Cron(it.trim()) },
    ).single().required()
}

public class ScheduleListCommand : KodexAgentToolCommand(
    "list",
    "List active persisted schedules targeting the Agent.",
)

public class ScheduleDeleteCommand : KodexToolLeafCommand(
    "delete",
    "Delete an active schedule by its schedule-id.",
) {
    public val scheduleId: Long by argument("schedule-id", help = "Schedule ID to delete.").long()
}

public class SearchCommand : KodexAgentToolCommand(
    "search",
    "Execute web search or page inspection commands via the backend.",
) {
    public val queries: List<String> by option("-q", "--query", metavar = "QUERY", help = "Web search query (repeatable).").multiple()
    public val domains: List<String> by option("--domain", metavar = "DOMAIN", help = "Domain filter for -q/--query (repeatable).").multiple()
    public val recency: Long? by option("--recency", metavar = "DAYS", help = "Recency filter in days for -q/--query.")
        .long().check("Recency days must be non-negative.") { it >= 0L }
    public val payload: CliPayloadInput<SearchCommands>? by jsonPayloadOptions(
        SearchCommands.serializer(),
        "Full SearchCommands JSON object (search_query, image_query, open, click, find, finance, weather, sports, time).",
    )

    public fun resolveCommands(): CliPayloadInput<SearchCommands> {
        if ((domains.isNotEmpty() || recency != null) && queries.isEmpty()) {
            throw UsageError("--domain and --recency require at least one -q/--query.")
        }
        val explicitPayload = payload
        if (queries.isNotEmpty() == (explicitPayload != null)) {
            throw UsageError("Provide either -q/--query or (--json / --stdin).")
        }
        return explicitPayload ?: CliPayloadInput.Inline(
            SearchCommands(
                searchQuery = queries.map { q ->
                    SearchQuery(q = q, recency = recency, domains = domains.ifEmpty { null })
                },
            ),
        )
    }

    override fun validate() {
        resolveCommands()
    }
}

public class ImageCommand : KodexAgentToolCommand(
    "image",
    "Generate or edit a PNG image via the backend and print its saved path (inspect with read_media).",
) {
    public val prompt: String by option("-p", "--prompt", metavar = "PROMPT", help = "Image generation or edit prompt.").required()
    public val referencedImagePaths: List<String> by option(
        "-i",
        "--image",
        metavar = "PATH",
        help = "Local reference image path for editing (repeatable; relative paths resolve against Agent cwd).",
    ).multiple()
}

/** `kodex mcp (list | status | reconnect | enable | disable)` — MCP server meta-management domain. */
public class McpCommand : KodexGroupCommand(
    "mcp",
    "Inspect, enable, disable, or reconnect configured MCP servers.",
    McpListCommand(),
    McpStatusCommand(),
    McpReconnectCommand(),
    McpEnableCommand(),
    McpDisableCommand(),
    isToolGroup = true,
)

public class McpListCommand : KodexToolLeafCommand(
    "list",
    "List all configured MCP servers and their connection/auth states.",
)

public class McpStatusCommand : KodexToolLeafCommand(
    "status",
    "Show sanitized status and published tools for one configured MCP server.",
) {
    public val serverName: String by argument("server", help = "Configured MCP server name.")
}

public class McpReconnectCommand : KodexToolLeafCommand(
    "reconnect",
    "Reconnect an enabled MCP server and refresh its published tool catalog.",
) {
    public val serverName: String by argument("server", help = "Enabled MCP server name.")
}

public sealed class McpSetEnabledCommand(
    name: String,
    summary: String,
    public val enabled: Boolean,
) : KodexToolLeafCommand(name, summary) {
    public val serverName: String by argument("server", help = "Configured MCP server name.")
}

public class McpEnableCommand : McpSetEnabledCommand(
    "enable",
    "Enable a configured MCP server in backend settings.",
    enabled = true,
)

public class McpDisableCommand : McpSetEnabledCommand(
    "disable",
    "Disable a configured MCP server in backend settings while retaining its credentials.",
    enabled = false,
)

/**
 * Dynamic top-level domain mounted as `kodex <server>` for one enabled MCP server, exposing each
 * published [McpCatalogTool] as a direct subcommand (`kodex <server> <tool>`).
 */
public class McpServerDomainCommand(
    public val serverName: String,
    serverInstructions: String = "",
    tools: List<McpCatalogTool>,
) : KodexGroupCommand(
    serverName,
    serverInstructions.ifBlank { "Invoke tools published by MCP server '$serverName'." },
    *tools.map(::McpServerToolCommand).toTypedArray(),
    isToolGroup = true,
)

/**
 * Dynamic leaf command `kodex <server> <tool>` backed by [tool]; accepts `--json` or `--stdin`
 * (defaulting to `{}`) and renders [McpCatalogTool.inputSchema] in `--help` epilog.
 */
public class McpServerToolCommand(
    public val tool: McpCatalogTool,
) : KodexToolLeafCommand(
    tool.toolName,
    tool.description.ifBlank { "Invoke MCP tool '${tool.toolName}' on '${tool.serverName}'." },
) {
    public val arguments: CliPayloadInput<JsonObject> by jsonPayloadOptions(
        JsonObject.serializer(),
        "Tool arguments JSON object matching the input schema below (defaults to {} when omitted).",
    ).default(CliPayloadInput.Inline(JsonObject(emptyMap())))

    override fun helpEpilog(context: Context): String =
        "Input JSON Schema:\n" + CliToolJson.encodeToString(ObjectPropertyDefinition.serializer(), tool.inputSchema)
}

public class ToolCommand : KodexGroupCommand(
    "tool",
    "Discover available Kodex CLI and MCP tool commands.",
    ToolListCommand(),
    isToolGroup = true,
)

public class ToolListCommand : KodexToolLeafCommand(
    "list",
    "Print '<command>\\t<summary>' lines for built-in CLI tools and enabled MCP tools.",
)
