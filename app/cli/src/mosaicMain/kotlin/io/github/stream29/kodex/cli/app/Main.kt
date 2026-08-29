package io.github.stream29.kodex.cli.app

import com.jakewharton.mosaic.runMosaic
import com.jakewharton.mosaic.terminal.MouseTracking
import com.jakewharton.mosaic.terminal.TerminalScreen
import io.github.stream29.kodex.utils.kodexhome.KodexHome
import io.github.stream29.kodex.utils.logging.initializeLogging
import kotlinx.coroutines.runBlocking

public fun main() {
    try {
        runBlocking { initializeLogging(KodexHome) }
    } catch (failure: Throwable) {
        println("Unable to initialize Kodex logging: ${failure.message ?: failure}")
        return
    }
    runBlocking(CliCoroutineExceptionLogger) {
        val application = try {
            KodexApplication.openDefault()
        } catch (failure: Throwable) {
            println("Unable to start Kodex: ${failure.message ?: failure}")
            return@runBlocking
        }
        try {
            try {
                runMosaic(
                    mouseTracking = MouseTracking.AnyEvents,
                    screen = TerminalScreen.Alternate,
                ) {
                    SessionTreeCliScreen(
                        viewModel = application.viewModel,
                        newLineKey = application.newLineKey,
                        sidebarSettings = application.sidebarSettings,
                    )
                }
            } finally {
                resetTerminalTitle()
            }
        } finally {
            application.shutdown()
        }
    }
}
