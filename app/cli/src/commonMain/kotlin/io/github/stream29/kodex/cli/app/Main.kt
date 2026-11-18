package io.github.stream29.kodex.cli.app

import dev.kauri.framework.openEmbeddedResource
import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.backend.BackendInstance
import io.github.stream29.kodex.backend.backendProcessAlive
import io.github.stream29.kodex.backend.backendStopSignalReceived
import io.github.stream29.kodex.backend.exitKodexProcess
import io.github.stream29.kodex.backend.host.connectBackend
import io.github.stream29.kodex.backend.host.ensureBackend
import io.github.stream29.kodex.backend.host.runBackendHost
import io.github.stream29.kodex.backend.installBackendSignals
import io.github.stream29.kodex.backend.randomBackendPassword
import io.github.stream29.kodex.rpc.control.backendControlRequest
import io.github.stream29.kodex.rpc.local.withLocalRpcClient
import io.github.stream29.kodex.utils.kodexhome.KodexHome
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.processId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path

public actual fun main(args: Array<String>) {
    initializeCliLogging()
    val home = KodexHome
    val result = runBlocking(CliCoroutineExceptionLogger) {
        dispatchCliWithToolRpc(
            arguments = args.asList(),
            defaultHome = home,
            output = ::printCliMessage,
        ) { command, invokeTool ->
            runLeafCommand(command, home, invokeTool)
        }
    }
    if (result != 0) exitKodexProcess(result)
}

internal fun initializeCliLogging() {
    KotlinLoggingConfiguration.logStartupMessage = false
    // macOS defaults to DarwinLoggerFactory, which warns on direct configuration.
    KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
    KotlinLoggingConfiguration.direct.logLevel = Level.OFF
}

private suspend fun runLeafCommand(
    command: KodexLeafCommand,
    home: Path,
    invokeTool: suspend (KodexToolLeafCommand) -> Int,
): Int {
    var result = 0
    val outputHandler: (String) -> Unit = if (command.isToolCommand) {
        { message -> printCliMessage(message, true) }
    } else {
        ::println
    }
    return runCliSafely(
        backendStart = command is BackendStartCommand,
        processId = ::processId,
        reportFailure = { logCliFailure(it, home) },
        output = outputHandler,
    ) {
        when (command) {
            is WebCommand -> {
                val cwd = SystemCoroutineFileSystem.resolve(Path("."))
                val (_, endpoint) = resolveFrontendBackend(home, command.backend.resolve())
                withLocalFrontendHost(
                    endpoint, cwd.toString(), ::openEmbeddedResource, ::randomBackendPassword,
                ) { url ->
                    installBackendSignals()
                    result = runLocalWebFrontend(
                        url, window = false,
                        stopRequested = ::backendStopSignalReceived, engine = LocalAppEngine.Default,
                    )
                }
            }
            is AppCommand -> {
                val engine = resolveLocalAppEngine(
                    when (command.engine ?: CliAppEngine.Default) {
                        CliAppEngine.Default -> LocalAppEngine.Default
                        CliAppEngine.Chrome -> LocalAppEngine.Chrome
                        CliAppEngine.System -> LocalAppEngine.System
                    },
                )
                val cwd = SystemCoroutineFileSystem.resolve(Path("."))
                withLocalFrontendHost(
                    ensureBackend(home), cwd.toString(),
                    ::openEmbeddedResource, ::randomBackendPassword,
                ) { url ->
                    installBackendSignals()
                    result = runLocalWebFrontend(
                        url, window = true,
                        stopRequested = ::backendStopSignalReceived, engine = engine,
                    )
                }
            }
            is BackendStartCommand -> runBackendHost(home)
            is BackendControlCliCommand -> {
                try {
                    val endpoint = BackendInstance.discover(home)
                    if (endpoint == null) {
                        println("""{"phase":"not_running"}""")
                        if (command.control != BackendControlCommand.Status) result = 1
                    } else {
                        println(backendControlRequest(endpoint, command.control.commandName))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    println("""{"phase":"unavailable","error":"backend_cannot_serve_command"}""")
                    result = 1
                }
            }
            is TuiCommand -> {
                val cwd = SystemCoroutineFileSystem.resolve(Path("."))
                val (backendHome, endpoint) = resolveFrontendBackend(home, command.backend.resolve())
                initializeTuiLogging(home, ::backendProcessAlive)
                withLocalRpcClient(backendHome, endpoint) { connection ->
                    withFrontendApplication(connection, cwd) { application ->
                        runTuiApplication(application)
                    }
                }
            }
            is KodexToolLeafCommand -> {
                result = invokeTool(command)
            }
        }
        result
    }
}

internal suspend fun resolveFrontendBackend(
    home: Path,
    target: CliBackendTarget?,
    ensure: suspend (Path) -> BackendEndpoint = ::ensureBackend,
    connect: suspend (Path, Int) -> BackendEndpoint = ::connectBackend,
): Pair<Path, BackendEndpoint> {
    if (target == null || target.port == null) return home to ensure(home)
    val rawHome = target.backendHome
    val backendHome = if (rawHome == null) home else resolveExistingOrCandidatePath(Path(rawHome))
    return backendHome to connect(backendHome, target.port)
}
