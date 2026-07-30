package io.github.stream29.codex.lite.cli.app

import com.jakewharton.mosaic.terminal.MouseTracking
import com.jakewharton.mosaic.terminal.TerminalScreen
import io.github.stream29.codex.lite.utils.codexlitehome.CodexLiteHome
import io.github.stream29.codex.lite.utils.logging.initializeLogging
import io.github.stream29.codex.lite.utils.osenvironment.environmentVariable
import io.github.stream29.codex.lite.utils.osenvironment.requireUserHomeDirectory
import kotlinx.io.files.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

public fun main() {
    try {
        // Logging must be configured before constructing the application root coroutine context.
        runBlocking { initializeLogging(CodexLiteHome) }
    } catch (failure: Throwable) {
        println("Unable to initialize Codex Lite logging: ${failure.message ?: failure}")
        return
    }
    runBlocking(CliCoroutineExceptionLogger) {
    val application = try {
        val codexDirectory = environmentVariable("CODEX_HOME")
            ?.takeIf(String::isNotBlank)
            ?.let(::Path)
            ?: Path(requireUserHomeDirectory(), ".codex")
        CodexLiteApplication.open(codexDirectory = codexDirectory)
    } catch (failure: Throwable) {
        println("Unable to start Codex Lite: ${failure.message ?: failure}")
        return@runBlocking
    }
    try {
        coroutineScope {
            val exitRequested = CompletableDeferred<Unit>()
            val exitWatcher = launch {
                application.sessionViewModel.state.first { it.exitRequested }
                exitRequested.complete(Unit)
            }
            val mosaic = launch {
                com.jakewharton.mosaic.runMosaic(
                    mouseTracking = MouseTracking.AnyEvents,
                    screen = TerminalScreen.Alternate,
                ) {
                    SessionTreeCliScreen(application.sessionViewModel)
                }
            }
            mosaic.invokeOnCompletion { exitRequested.complete(Unit) }
            exitRequested.await()
            if (mosaic.isActive) mosaic.cancelAndJoin()
            exitWatcher.cancel()
        }
    } finally {
        application.shutdown()
    }
    }
}
