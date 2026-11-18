@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.kodex.cli.app

import com.github.ajalt.clikt.core.BaseCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import io.github.stream29.kodex.mcp.contract.ReservedMcpServerNames
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun validateBackendHomeValue(value: String): String {
    require(value.isNotBlank() && '\u0000' !in value) { "Backend Home path must not be blank." }
    return value
}

/** Explicit local-loopback backend target (`--backend-url` / `--backend-home`). */
public data class CliBackendTarget(
    public val port: Int? = null,
    public val backendHome: String? = null,
) {
    init {
        require(port != null || backendHome != null) {
            "CliBackendTarget requires at least a loopback port or a backendHome path."
        }
        if (port != null) require(port in 1..65535) { "Invalid backend URL port: $port" }
        if (backendHome != null) validateBackendHomeValue(backendHome)
    }
}

/** Webview engine selection for `kodex app`. */
public enum class CliAppEngine { Default, Chrome, System }

/** Subcommands under `kodex backend`. */
public enum class BackendControlCommand(
    public val commandName: String,
    public val description: String,
) {
    Status("status", "Show backend status."),
    Stop("stop", "Request cooperative backend shutdown."),
    Reload("reload", "Reload backend settings from disk."),
}

/** Result payload returned by `kodex agent status --agent ID`. */
@Serializable
public data class CliAgentStatusOutput(
    @SerialName("agent_id")
    public val agentId: Int,
    @SerialName("thread_name")
    public val threadName: String,
    public val cwd: String,
    public val model: OpenAiModelId,
    @SerialName("reasoning_effort")
    public val reasoningEffort: ReasoningEffort? = null,
    @SerialName("service_tier")
    public val serviceTier: ServiceTier? = null,
    @SerialName("request_user_input_mode")
    public val requestUserInputMode: RequestUserInputMode,
    public val running: Boolean,
    @SerialName("latest_index")
    public val latestIndex: Int,
    public val now: String,
    @SerialName("context_window")
    public val contextWindow: CliContextWindowSummary? = null,
)

/** Context-window token budget snapshot included in [CliAgentStatusOutput]. */
@Serializable
public data class CliContextWindowSummary(
    @SerialName("effective_window_tokens")
    public val effectiveWindowTokens: Long,
    @SerialName("used_tokens")
    public val usedTokens: Long? = null,
    @SerialName("remaining_tokens")
    public val remainingTokens: Long? = null,
    @SerialName("remaining_percent")
    public val remainingPercent: Int? = null,
)

/** Strict JSON codec shared by CLI option/stdin payload decoding and JSON stdout formatting. */
public expect val CliToolJson: Json

/** Validates a local loopback HTTP URL (`http://127.0.0.1:<port>` or `http://localhost:<port>`) and returns `<port>`. */
public expect fun parseLocalBackendUrlPort(value: String): Int

/**
 * Parses [arguments] against [KodexCommand] (entering [withMcpDomains] only when [arguments]
 * targets a non-built-in top-level domain so callers can hold a single lexical RPC connection
 * across discovery and [execute]) and invokes [execute] on the resolved [KodexLeafCommand],
 * writing help or usage diagnostics via [output].
 */
public expect suspend fun dispatchCli(
    arguments: List<String>,
    output: (message: String, error: Boolean) -> Unit,
    withMcpDomains: suspend (
        target: CliBackendTarget?,
        block: suspend (List<McpServerDomainCommand>) -> Int,
    ) -> Int = { _, block -> block(emptyList()) },
    execute: suspend (KodexLeafCommand) -> Int,
): Int

/** Base node in the `kodex` Clikt command tree. */
public sealed class KodexCliCommand(
    name: String,
    public val summary: String,
) : BaseCliktCommand<KodexCliCommand>(name) {
    override fun help(context: Context): String = summary
}

/** Non-leaf command group that prints its help text when invoked without a subcommand. */
public abstract class KodexGroupCommand(
    name: String,
    summary: String,
    vararg children: KodexCliCommand,
    public val isToolGroup: Boolean = false,
) : KodexCliCommand(name, summary) {
    override val invokeWithoutSubcommand: Boolean = true

    init {
        if (children.isNotEmpty()) subcommands(*children)
    }
}

/** Executable leaf command; serves directly as the parsed command value passed to [dispatchCli]. */
public sealed class KodexLeafCommand(
    name: String,
    summary: String,
    public val isToolCommand: Boolean = false,
) : KodexCliCommand(name, summary) {
    public open fun validate(): Unit = Unit
}

/** Executable thin-client tool leaf command sharing [ToolBackendOptions]. */
public sealed class KodexToolLeafCommand(
    name: String,
    summary: String,
) : KodexLeafCommand(name, summary, isToolCommand = true) {
    public val backend: ToolBackendOptions by ToolBackendOptions()
}

/** Executable Agent-targeted thin-client tool command sharing [AgentTargetOptions] and [ToolBackendOptions]. */
public sealed class KodexAgentToolCommand(
    name: String,
    summary: String,
) : KodexToolLeafCommand(name, summary) {
    public val agent: AgentTargetOptions by AgentTargetOptions()
}

/** Interactive frontend backend options (`--backend-home` requires `--backend-url`). */
public class FrontendBackendOptions : OptionGroup() {
    public val backendPort: Int? by option(
        "--backend-url",
        metavar = "URL",
        help = "Connect only to this running loopback HTTP backend URL without starting or replacing a backend.",
    ).convert { parseLocalBackendUrlPort(it) }

    public val backendHome: String? by option(
        "--backend-home",
        metavar = "DIR",
        help = "Read the target backend's lock and connection record from this Kodex Home (requires --backend-url).",
    ).convert { validateBackendHomeValue(it) }

    public fun resolve(): CliBackendTarget? {
        val port = backendPort
        val home = backendHome
        if (port == null) {
            if (home != null) throw UsageError("--backend-home requires --backend-url.", paramName = "--backend-home")
            return null
        }
        return CliBackendTarget(port = port, backendHome = home)
    }
}

/** Thin tool backend options (never auto-starts a daemon; allows `--backend-home` alone). */
public class ToolBackendOptions : OptionGroup() {
    public val backendPort: Int? by option(
        "--backend-url",
        metavar = "URL",
        help = "Connect to this running loopback HTTP backend URL.",
    ).convert { parseLocalBackendUrlPort(it) }

    public val backendHome: String? by option(
        "--backend-home",
        metavar = "DIR",
        help = "Read the running backend connection record from this Kodex Home (defaults to KODEX_HOME or ~/.kodex).",
    ).convert { validateBackendHomeValue(it) }

    public fun resolve(): CliBackendTarget? {
        val port = backendPort
        val home = backendHome
        return if (port == null && home == null) null else CliBackendTarget(port = port, backendHome = home)
    }
}

/** Agent session selection option (`--agent` / `-a`, defaulting to `KODEX_AGENT_ID` at runtime). */
public class AgentTargetOptions : OptionGroup() {
    public val agentId: Int? by option(
        "--agent",
        "-a",
        metavar = "AGENT_ID",
        help = "Target Agent session ID (defaults to KODEX_AGENT_ID).",
    ).int().check("Agent ID must be non-negative.") { it >= 0 }
}

/**
 * Root `kodex` command tree.
 *
 * Built-in domains ([ReservedMcpServerNames]) are always registered first; dynamically discovered
 * [mcpDomains] with non-conflicting names mount alongside them as `kodex <server> <tool>`.
 */
public class KodexCommand(
    mcpDomains: List<McpServerDomainCommand> = emptyList(),
) : KodexGroupCommand(
    "kodex",
    "Kodex CLI: launch a frontend, manage the local backend daemon, or invoke Agent and MCP tool commands.",
    TuiCommand(),
    WebCommand(),
    AppCommand(),
    BackendCommand(),
    TerminalCommand(),
    AskCommand(),
    ScheduleCommand(),
    SessionCommand(),
    AgentCommand(),
    SearchCommand(),
    ImageCommand(),
    McpCommand(),
    ToolCommand(),
    *mcpDomains.filter { it.commandName !in ReservedMcpServerNames }.toTypedArray(),
) {
    init {
        context { helpOptionNames = setOf("-h", "--help") }
    }
}

public class TuiCommand : KodexLeafCommand("tui", "Open the terminal user interface.") {
    public val backend: FrontendBackendOptions by FrontendBackendOptions()
    override fun validate() {
        backend.resolve()
    }
}

public class WebCommand : KodexLeafCommand(
    "web",
    "Serve the local frontend and open the system browser.",
) {
    public val backend: FrontendBackendOptions by FrontendBackendOptions()
    override fun validate() {
        backend.resolve()
    }
}

public class AppCommand : KodexLeafCommand("app", "Open the local frontend in an application window.") {
    public val engine: CliAppEngine? by option(
        "--engine",
        metavar = "chrome|system",
        help = "Choose Chrome (Linux only) or the platform system WebView.",
    ).convert {
        when (it) {
            "chrome" -> CliAppEngine.Chrome
            "system" -> CliAppEngine.System
            else -> throw IllegalArgumentException("Choose chrome or system.")
        }
    }
}

public class BackendCommand : KodexGroupCommand(
    "backend",
    "Manage the independent local backend.",
    BackendStartCommand(),
    *BackendControlCommand.entries.map(::BackendControlCliCommand).toTypedArray(),
)

public class BackendStartCommand : KodexLeafCommand("start", "Run the backend in the foreground.")

public class BackendControlCliCommand(
    public val control: BackendControlCommand,
) : KodexLeafCommand(control.commandName, control.description)
