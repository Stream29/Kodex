package io.github.stream29.kodex.cli.app

import dev.kauri.framework.openEmbeddedResource
import io.github.stream29.kodex.backend.randomBackendPassword
import io.github.stream29.kodex.backend.backendProcessAlive
import io.github.stream29.kodex.backend.BackendInstance
import io.github.stream29.kodex.backend.exitKodexProcess
import io.github.stream29.kodex.backend.installBackendSignals
import io.github.stream29.kodex.backend.backendStopSignalReceived
import io.github.stream29.kodex.backend.host.ensureBackend
import io.github.stream29.kodex.backend.host.runBackendHost
import io.github.stream29.kodex.rpc.control.backendControlRequest
import io.github.stream29.kodex.rpc.local.withLocalRpcClient
import io.github.stream29.kodex.utils.kodexhome.KodexHome
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.processId
import kotlinx.coroutines.*
import kotlinx.io.files.Path

public actual fun main(args: Array<String>) {
    val result = dispatchCli(args.asList(), ::printCliMessage, ::runInvocation)
    if (result != 0) exitKodexProcess(result)
}

private fun runInvocation(invocation: CliInvocation): Int {
    var result = 0
    val backendStart = invocation is CliInvocation.BackendStart
    try {
        runBlocking(CliCoroutineExceptionLogger) {
            val home = KodexHome
            when (invocation) {
                CliInvocation.Web, CliInvocation.App -> {
                    val cwd = SystemCoroutineFileSystem.resolve(Path("."))
                    withLocalFrontendHost(ensureBackend(home), cwd.toString(),
                        ::openEmbeddedResource, ::randomBackendPassword) { url ->
                        installBackendSignals()
                        result = runLocalWebFrontend(
                            url, window = invocation == CliInvocation.App,
                            stopRequested = ::backendStopSignalReceived,
                        )
                    }
                }
                is CliInvocation.BackendStart -> runBackendHost(
                    home, browserOrigins = invocation.browserOrigins,
                )
                is CliInvocation.BackendControl -> {
                    try {
                        val endpoint = BackendInstance.discover(home)
                        if (endpoint == null) {
                            println("""{"phase":"not_running"}""")
                            if (invocation.command != BackendControlCommand.Status) result = 1
                        } else println(backendControlRequest(endpoint, invocation.command.commandName))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        println("""{"phase":"unavailable","error":"backend_cannot_serve_command"}""")
                        result = 1
                    }
                }
                CliInvocation.Tui -> Unit
            }
            if (invocation != CliInvocation.Tui) {
                return@runBlocking
            }
            val cwd = SystemCoroutineFileSystem.resolve(Path("."))
            val endpoint = ensureBackend(home)
            initializeTuiLogging(home, ::backendProcessAlive)
            withLocalRpcClient(home, endpoint) { connection ->
                withFrontendApplication(connection, cwd, NativeFrontendHost,
                    io.github.stream29.kodex.cli.rpc.LocalRpcFrontendEnvironment) { application ->
                    runTuiApplication(application)
                }
            }
        }
    } catch (_: CancellationException) {
        result = 130
    } catch (failure: Throwable) {
        val prefix = if (backendStart) "Unable to run Kodex [pid=${processId()}]: "
            else "Unable to run Kodex: "
        println(prefix + (failure.message ?: "operation failed"))
        result = 1
    }
    return result
}
