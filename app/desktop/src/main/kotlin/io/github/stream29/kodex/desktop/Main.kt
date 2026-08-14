package io.github.stream29.kodex.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.cli.app.KodexApplication
import io.github.stream29.kodex.desktop.application.ApplicationDesktopView
import io.github.stream29.kodex.desktop.application.KodexDesktopTheme
import io.github.stream29.kodex.utils.kodexhome.KodexHome
import io.github.stream29.kodex.utils.logging.global
import io.github.stream29.kodex.utils.logging.initializeLogging
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.runBlocking

/** JVM Desktop process entrypoint. */
public fun main(): Unit {
    try {
        runBlocking { initializeLogging(KodexHome) }
    } catch (failure: Throwable) {
        showStartupFailure("Unable to initialize Kodex logging", failure)
        return
    }

    runBlocking(DesktopCoroutineExceptionLogger) {
        val kodexApplication = try {
            KodexApplication.openDefault()
        } catch (failure: Throwable) {
            showStartupFailure("Unable to start Kodex", failure)
            return@runBlocking
        }
        try {
            application {
                Window(
                    onCloseRequest = ::exitApplication,
                    title = "Kodex",
                    state = WindowState(width = 1360.dp, height = 900.dp),
                ) {
                    window.minimumSize = java.awt.Dimension(760, 600)
                    KodexDesktopTheme(
                        darkTheme = rememberDesktopSystemDarkTheme(),
                    ) {
                        ApplicationDesktopView(
                            viewModel = kodexApplication.viewModel,
                            newLineKey = kodexApplication.newLineKey,
                        )
                    }
                }
            }
        } finally {
            kodexApplication.shutdown()
        }
    }
}

private fun showStartupFailure(title: String, failure: Throwable): Unit {
    val detail = failure.message?.trim()?.takeIf(String::isNotEmpty) ?: failure.toString()
    runCatching {
        javax.swing.JOptionPane.showMessageDialog(
            null,
            "$title:\n$detail",
            "Kodex",
            javax.swing.JOptionPane.ERROR_MESSAGE,
        )
    }.onFailure {
        System.err.println("$title: $detail")
    }
}

private val DesktopCoroutineExceptionLogger = CoroutineExceptionHandler { context, failure ->
    DesktopLogger.error(failure) { "Unhandled Desktop coroutine failure in $context" }
}

private val DesktopLogger by lazy {
    KotlinLogging.logger {}.global()
}
