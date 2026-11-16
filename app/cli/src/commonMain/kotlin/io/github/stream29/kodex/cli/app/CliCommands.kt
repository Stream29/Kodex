package io.github.stream29.kodex.cli.app

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parsers.CommandLineParser
import io.github.stream29.kodex.mcp.contract.ReservedMcpServerNames
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
public actual val CliToolJson: Json = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

private val backendUrlPattern = Regex("""http://(127\.0\.0\.1|localhost):([1-9][0-9]{0,4})""")

public actual fun parseLocalBackendUrlPort(value: String): Int {
    val match = requireNotNull(backendUrlPattern.matchEntire(value)) {
        "Use a loopback HTTP backend URL with an explicit port (http://127.0.0.1:<port> or http://localhost:<port>)."
    }
    val port = match.groupValues[2].toInt()
    require(port in 1..65535) { "Invalid backend URL port." }
    return port
}

public actual fun dispatchCli(
    arguments: List<String>,
    output: (message: String, error: Boolean) -> Unit,
    resolveMcpDomains: (CliBackendTarget?) -> List<McpServerDomainCommand>,
    execute: (KodexLeafCommand) -> Int,
): Int {
    val baseCommand = KodexCommand()
    val mcpDomains = try {
        val firstToken = arguments.firstOrNull()
        if (
            firstToken != null &&
            !firstToken.startsWith("-") &&
            !firstToken.startsWith("@") &&
            firstToken !in ReservedMcpServerNames
        ) {
            val helpRequested = "-h" in arguments || "--help" in arguments
            val target = try {
                extractDynamicBackendTarget(arguments.drop(1))
            } catch (error: UsageError) {
                if (helpRequested) null else throw error
            }
            resolveMcpDomains(target)
        } else {
            emptyList()
        }
    } catch (failure: CliktError) {
        baseCommand.getFormattedHelp(failure)?.let { message ->
            output(message, if (failure is PrintHelpMessage) failure.error else failure.printError)
        }
        return failure.statusCode
    }

    val command = if (mcpDomains.isEmpty()) baseCommand else KodexCommand(mcpDomains)
    var selected: KodexLeafCommand? = null
    try {
        CommandLineParser.parseAndRun(command, arguments) { invoked ->
            when (invoked) {
                is KodexGroupCommand -> {
                    if (invoked.currentContext.invokedSubcommand == null) {
                        throw PrintHelpMessage(invoked.currentContext)
                    }
                }
                is KodexLeafCommand -> {
                    invoked.validate()
                    selected = invoked
                }
            }
        }
    } catch (failure: CliktError) {
        command.getFormattedHelp(failure)?.let { message ->
            output(message, if (failure is PrintHelpMessage) failure.error else failure.printError)
        }
        return failure.statusCode
    }

    val leaf = checkNotNull(selected) { "No CLI leaf command selected." }
    if (leaf is FrontendHostCommand) {
        output(HostCommandMigrationNotice, true)
    }
    return execute(leaf)
}

internal fun collectToolListLines(
    command: KodexCliCommand,
    prefix: String = command.commandName,
): List<String> = when (command) {
    is KodexLeafCommand -> if (command.isToolCommand) {
        val singleLineSummary = command.summary.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        listOf("$prefix\t$singleLineSummary")
    } else {
        emptyList()
    }
    is KodexGroupCommand -> if (command.isToolGroup || command is KodexCommand) {
        command.registeredSubcommands().filterIsInstance<KodexCliCommand>().flatMap { child ->
            collectToolListLines(child, "$prefix ${child.commandName}")
        }
    } else {
        emptyList()
    }
}

private fun extractDynamicBackendTarget(tokens: List<String>): CliBackendTarget? {
    var port: Int? = null
    var home: String? = null
    var index = 0
    while (index < tokens.size) {
        val token = tokens[index]
        if (token == "--") break
        when {
            token == "--backend-url" -> {
                val raw = tokens.getOrNull(++index)
                    ?: throw UsageError("option --backend-url requires a value", paramName = "--backend-url")
                port = try {
                    parseLocalBackendUrlPort(raw)
                } catch (e: IllegalArgumentException) {
                    throw UsageError(e.message ?: "Invalid --backend-url", paramName = "--backend-url")
                }
            }
            token.startsWith("--backend-url=") -> {
                val raw = token.substringAfter("--backend-url=")
                port = try {
                    parseLocalBackendUrlPort(raw)
                } catch (e: IllegalArgumentException) {
                    throw UsageError(e.message ?: "Invalid --backend-url", paramName = "--backend-url")
                }
            }
            token == "--backend-home" -> {
                val raw = tokens.getOrNull(++index)
                    ?: throw UsageError("option --backend-home requires a value", paramName = "--backend-home")
                if (raw.isBlank() || '\u0000' in raw) {
                    throw UsageError("Backend Home path must not be blank.", paramName = "--backend-home")
                }
                home = raw
            }
            token.startsWith("--backend-home=") -> {
                val raw = token.substringAfter("--backend-home=")
                if (raw.isBlank() || '\u0000' in raw) {
                    throw UsageError("Backend Home path must not be blank.", paramName = "--backend-home")
                }
                home = raw
            }
        }
        index++
    }
    return if (port == null && home == null) null else CliBackendTarget(port = port, backendHome = home)
}
