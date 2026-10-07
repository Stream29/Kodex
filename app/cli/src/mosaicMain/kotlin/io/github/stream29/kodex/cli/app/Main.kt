package io.github.stream29.kodex.cli.app

import com.jakewharton.mosaic.runMosaic
import com.jakewharton.mosaic.terminal.MouseTracking
import com.jakewharton.mosaic.terminal.TerminalScreen
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.utils.kodexhome.KodexHome
import kotlinx.coroutines.runBlocking

public fun main() {
    runBlocking(CliCoroutineExceptionLogger) {
        val homeHandle = try {
            prepareKodexHome(KodexHome) { fromVersion, toVersion ->
                println(
                    "Migrating Kodex Home from $fromVersion to $toVersion. Please wait...",
                )
            }
        } catch (failure: Throwable) {
            println("Unable to prepare Kodex Home: ${failure.message ?: failure}")
            return@runBlocking
        }
        if (!initializeCliLogging(homeHandle)) return@runBlocking
        try {
            withKodexApplication(homeHandle) { application ->
                try {
                    runMosaic(
                        mouseTracking = MouseTracking.AnyEvents,
                        screen = TerminalScreen.Alternate,
                    ) {
                        SessionTreeCliScreen(
                            viewModel = application.viewModel,
                            newLineKey = application.newLineKey,
                            sidebarSettings = application.sidebarSettings,
                            onOperationFailure = application.reportUnhandledError,
                        )
                    }
                } finally {
                    resetTerminalTitle()
                }
            }
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            println("Unable to run Kodex: ${failure.message ?: failure}")
        }
    }
}
