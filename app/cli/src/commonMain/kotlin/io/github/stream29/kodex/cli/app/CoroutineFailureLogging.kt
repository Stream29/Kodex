package io.github.stream29.kodex.cli.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import io.github.stream29.kodex.utils.kodexhome.KodexHome
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.io.files.Path

/** Logs otherwise-unobserved failures from CLI-owned root coroutine scopes. */
internal val CliCoroutineExceptionLogger: CoroutineExceptionHandler = createCliCoroutineExceptionHandler()

internal fun createCliCoroutineExceptionHandler(
    home: () -> Path = { KodexHome },
    reportFailure: (Throwable, Path) -> Unit = ::logCliFailure,
    stderrOutput: (String) -> Unit = { printCliMessage(it, true) },
): CoroutineExceptionHandler = CoroutineExceptionHandler { context, failure ->
    val directActive = KotlinLoggingConfiguration.direct.logLevel != Level.OFF
    if (directActive) {
        CliLogger.error(failure) { "Unhandled CLI coroutine failure in $context" }
    }
    runCatching { reportFailure(failure, home()) }
    if (!directActive) {
        val summary = failure.message ?: failure::class.simpleName ?: "unknown failure"
        stderrOutput("Unhandled CLI coroutine failure: $summary")
    }
}

private val CliLogger by lazy {
    KotlinLogging.logger {}
}
