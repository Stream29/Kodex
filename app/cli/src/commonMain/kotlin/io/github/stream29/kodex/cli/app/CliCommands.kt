package io.github.stream29.kodex.cli.app

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import io.github.stream29.kodex.backend.canonicalLocalBrowserOrigin

internal data class CliBackendTarget(
    val port: Int,
    val backendHome: String? = null,
)

internal sealed interface CliInvocation {
    data class Tui(val backend: CliBackendTarget? = null) : CliInvocation
    data class Web(
        val origin: String? = null,
        val backend: CliBackendTarget? = null,
    ) : CliInvocation
    data class App(val engine: CliAppEngine = CliAppEngine.Default) : CliInvocation
    data class BackendStart(val browserOrigins: Set<String>) : CliInvocation
    data class BackendControl(val command: BackendControlCommand) : CliInvocation
}

internal enum class CliAppEngine { Default, Chrome, System }

internal enum class BackendControlCommand(val commandName: String, val description: String) {
    Status("status", "Show backend status."),
    Stop("stop", "Request cooperative backend shutdown."),
    Reload("reload", "Reload backend settings from disk."),
}

internal const val HostCommandMigrationNotice: String =
    "Note: 'kodex host --origin' has moved to 'kodex web --origin'; forwarding to 'kodex web --origin'."

private val backendUrlPattern = Regex("""http://(127\.0\.0\.1|localhost):([1-9][0-9]{0,4})""")

internal fun parseLocalBackendUrlPort(value: String): Int {
    val match = requireNotNull(backendUrlPattern.matchEntire(value)) {
        "Use a loopback HTTP backend URL with an explicit port (http://127.0.0.1:<port> or http://localhost:<port>)."
    }
    val port = match.groupValues[2].toInt()
    require(port in 1..65535) { "Invalid backend URL port." }
    return port
}

/**
 * Parse the entire command tree before entering the runtime boundary. Command run hooks only
 * select data: Clikt invokes parent hooks before validating their children's arguments.
 */
internal fun dispatchCli(
    arguments: List<String>,
    output: (message: String, error: Boolean) -> Unit,
    execute: (CliInvocation) -> Int,
): Int {
    var invocation: CliInvocation? = null
    val command = KodexCommand({ output(it, true) }) { invocation = it }
    try {
        command.parse(arguments)
    } catch (failure: CliktError) {
        command.getFormattedHelp(failure)?.let { message ->
            output(message, if (failure is PrintHelpMessage) failure.error else failure.printError)
        }
        return failure.statusCode
    }
    return execute(checkNotNull(invocation) { "No CLI invocation selected." })
}

private class KodexCommand(
    notify: (String) -> Unit,
    select: (CliInvocation) -> Unit,
) : CoreCliktCommand("kodex") {
    override val invokeWithoutSubcommand: Boolean = true

    init {
        context {
            helpOptionNames = setOf("-h", "--help")
        }
        subcommands(
            TuiCommand(select),
            WebCommand(select),
            AppCommand(select),
            FrontendHostCommand(notify, select),
            BackendCommand(select),
        )
    }

    override fun help(context: Context): String = "Kodex. Choose a frontend or manage the local backend."
    override fun run() {
        if (currentContext.invokedSubcommand == null) throw PrintHelpMessage(currentContext)
    }
}

private abstract class FrontendTargetCommand(name: String) : CoreCliktCommand(name) {
    private val backendPort by option(
        "--backend-url",
        metavar = "URL",
        help = "Connect only to this running loopback HTTP backend URL without starting or replacing a backend.",
    ).convert { parseLocalBackendUrlPort(it) }

    private val backendHome by option(
        "--backend-home",
        metavar = "DIR",
        help = "Read the target backend's lock and connection record from this Kodex Home (requires --backend-url).",
    ).convert {
        require(it.isNotBlank() && '\u0000' !in it) { "Backend Home path must not be blank." }
        it
    }

    protected fun resolveBackendTarget(): CliBackendTarget? {
        val port = backendPort
        val home = backendHome
        if (port == null) {
            if (home != null) {
                throw UsageError(
                    "--backend-home requires --backend-url.",
                    paramName = "--backend-home",
                )
            }
            return null
        }
        return CliBackendTarget(port = port, backendHome = home)
    }
}

private class TuiCommand(
    private val select: (CliInvocation) -> Unit,
) : FrontendTargetCommand("tui") {
    override fun help(context: Context): String = "Open the terminal user interface."
    override fun run() = select(CliInvocation.Tui(resolveBackendTarget()))
}

private class WebCommand(
    private val select: (CliInvocation) -> Unit,
) : FrontendTargetCommand("web") {
    private val origin by option(
        "--origin",
        metavar = "ORIGIN",
        help = "Trust this exact loopback HTTP static frontend Origin for one scoped pairing.",
    ).convert { canonicalLocalBrowserOrigin(it) }

    override fun help(context: Context): String =
        "Serve the local frontend and open the system browser, or pair an independently served website."
    override fun run() = select(CliInvocation.Web(origin = origin, backend = resolveBackendTarget()))
}

private class AppCommand(private val select: (CliInvocation) -> Unit) : CoreCliktCommand("app") {
    private val engine by option(
        "--engine", metavar = "chrome|system",
        help = "Choose Chrome (Linux only) or the platform system WebView.",
    ).convert {
        when (it) {
            "chrome" -> CliAppEngine.Chrome
            "system" -> CliAppEngine.System
            else -> throw IllegalArgumentException("Choose chrome or system.")
        }
    }

    override fun help(context: Context): String = "Open the local frontend in an application window."
    override fun run() = select(CliInvocation.App(engine ?: CliAppEngine.Default))
}

private class FrontendHostCommand(
    private val notify: (String) -> Unit,
    private val select: (CliInvocation) -> Unit,
) : FrontendTargetCommand("host") {
    private val origin by option(
        "--origin", metavar = "ORIGIN",
        help = "Trust this exact loopback HTTP static frontend Origin for one scoped pairing.",
    ).convert { canonicalLocalBrowserOrigin(it) }.required()

    override fun help(context: Context): String =
        "Deprecated: use 'kodex web --origin'. Run a local frontend server to pair an independently served website."
    override fun run() {
        val target = resolveBackendTarget()
        notify(HostCommandMigrationNotice)
        select(CliInvocation.Web(origin = origin, backend = target))
    }
}

private class BackendCommand(select: (CliInvocation) -> Unit) : CoreCliktCommand("backend") {
    override val invokeWithoutSubcommand: Boolean = true

    init {
        subcommands(
            BackendStartCommand(select),
            *BackendControlCommand.entries.map { BackendControlCliCommand(it, select) }.toTypedArray(),
        )
    }

    override fun help(context: Context): String = "Manage the independent local backend."
    override fun run() {
        if (currentContext.invokedSubcommand == null) throw PrintHelpMessage(currentContext)
    }
}

private class BackendStartCommand(private val select: (CliInvocation) -> Unit) : CoreCliktCommand("start") {
    private val browserOrigins by option(
        "--web-origin",
        metavar = "ORIGIN",
        help = "Allow a loopback HTTP origin for manual browser access. Repeat to allow multiple origins; disabled by default.",
    ).convert { canonicalLocalBrowserOrigin(it) }.multiple()

    override fun help(context: Context): String = "Run the backend in the foreground."
    override fun run() = select(CliInvocation.BackendStart(browserOrigins.toSet()))
}

private class BackendControlCliCommand(
    private val control: BackendControlCommand,
    private val select: (CliInvocation) -> Unit,
) : CoreCliktCommand(control.commandName) {
    override fun help(context: Context): String = control.description
    override fun run() = select(CliInvocation.BackendControl(control))
}
