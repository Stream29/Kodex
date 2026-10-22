package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jakewharton.mosaic.runMosaic
import com.jakewharton.mosaic.terminal.MouseTracking
import com.jakewharton.mosaic.terminal.TerminalScreen
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.layout.height
import io.github.stream29.kodex.backend.BackendInstance
import io.github.stream29.kodex.backend.exitKodexProcess
import io.github.stream29.kodex.backend.host.ensureBackendForTui
import io.github.stream29.kodex.backend.host.runBackendHost
import io.github.stream29.kodex.rpc.local.BackendConnectionState
import io.github.stream29.kodex.rpc.control.backendControlRequest
import io.github.stream29.kodex.rpc.local.withLocalRpcClient
import io.github.stream29.kodex.utils.kodexhome.KodexHome
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.processId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path

public actual fun main(args: Array<String>) {
    var result = 0
    try {
        runBlocking(CliCoroutineExceptionLogger) {
            val home = KodexHome
            if (args.isNotEmpty()) {
                when {
                    args.contentEquals(arrayOf("backend", "start")) -> runBackendHost(home)
                    args.size == 2 && args[0] == "backend" && args[1] in setOf("status", "stop", "reload") -> {
                        try {
                            val endpoint = BackendInstance.discover(home)
                            if (endpoint == null) {
                                println("""{"phase":"not_running"}""")
                                if (args[1] != "status") result = 1
                            } else println(backendControlRequest(endpoint, args[1]))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            println("""{"phase":"unavailable","error":"backend_cannot_serve_command"}""")
                            result = 1
                        }
                    }
                    args.contentEquals(arrayOf("--help")) || args.contentEquals(arrayOf("help")) ->
                        println("kodex | kodex backend start | status | stop | reload")
                    else -> { println("Unknown command. Run kodex --help."); result = 2 }
                }
                return@runBlocking
            }
            val cwd = SystemCoroutineFileSystem.resolve(Path("."))
            val endpoint = ensureBackendForTui(home)
            initializeTuiLogging(home)
            withLocalRpcClient(home, endpoint) { connection ->
                withFrontendApplication(connection, cwd) { application ->
                    try {
                        runMosaic(mouseTracking = MouseTracking.AnyEvents, screen = TerminalScreen.Alternate) {
                            val state by application.connectionState.collectAsState()
                            val failureReason by application.connectionFailureReason.collectAsState()
                            Column {
                                if (state != BackendConnectionState.Connected) {
                                    Text(
                                        if (state == BackendConnectionState.Failed) failureReason?.let { "Backend failed: $it" }
                                            ?: "Backend ended or changed. Restart this TUI."
                                        else "Backend disconnected. Reconnecting to the same instance...",
                                        modifier = Modifier.height(1),
                                        color = Color.Yellow,
                                    )
                                }
                                SessionTreeCliScreen(
                                    viewModel = application.viewModel,
                                    newLineKey = application.newLineKey,
                                    sidebarSettings = application.sidebarSettings,
                                    onOperationFailure = application.reportUnhandledError,
                                    reservedRows = if (state == BackendConnectionState.Connected) 0 else 1,
                                )
                            }
                        }
                    } finally { resetTerminalTitle() }
                }
            }
        }
    } catch (_: CancellationException) {
        result = 130
    } catch (failure: Throwable) {
        val prefix = if (args.contentEquals(arrayOf("backend", "start"))) "Unable to run Kodex [pid=${processId()}]: "
            else "Unable to run Kodex: "
        println(prefix + (failure.message ?: "operation failed"))
        result = 1
    }
    if (result != 0) exitKodexProcess(result)
}
