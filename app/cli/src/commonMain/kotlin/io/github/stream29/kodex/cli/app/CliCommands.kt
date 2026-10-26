package io.github.stream29.kodex.cli.app

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import io.github.stream29.kodex.backend.canonicalLocalBrowserOrigin

internal sealed interface CliInvocation {
    data object Tui : CliInvocation
    data object Web : CliInvocation
    data object App : CliInvocation
    data class BackendStart(val browserOrigins: Set<String>) : CliInvocation
    data class BackendControl(val command: BackendControlCommand) : CliInvocation
}

internal enum class BackendControlCommand(val commandName: String, val description: String) {
    Status("status", "Show backend status."),
    Stop("stop", "Request cooperative backend shutdown."),
    Reload("reload", "Reload backend settings from disk."),
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
    val command = KodexCommand { invocation = it }
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

private class KodexCommand(select: (CliInvocation) -> Unit) : CoreCliktCommand("kodex") {
    private val selectInvocation = select
    override val invokeWithoutSubcommand: Boolean = true

    init {
        context {
            helpOptionNames = setOf("-h", "--help")
        }
        subcommands(
            FrontendCommand("web", "Serve the local frontend and open the system browser.", CliInvocation.Web, select),
            FrontendCommand("app", "Open the local frontend in a system WebView window.", CliInvocation.App, select),
            BackendCommand(select),
        )
    }

    override fun help(context: Context): String = "Kodex. Run without a subcommand to start the TUI."
    override fun helpEpilog(context: Context): String = "The legacy 'kodex help' command is an alias for 'kodex --help'."
    override fun aliases(): Map<String, List<String>> = mapOf("help" to listOf("--help"))
    override fun run() {
        if (currentContext.invokedSubcommand == null) selectInvocation(CliInvocation.Tui)
    }
}

private class FrontendCommand(
    name: String,
    private val description: String,
    private val invocation: CliInvocation,
    private val select: (CliInvocation) -> Unit,
) : CoreCliktCommand(name) {
    override fun help(context: Context): String = description
    override fun run() = select(invocation)
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
