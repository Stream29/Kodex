package io.github.stream29.kodex.cli.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.utils.logging.global
import kotlinx.coroutines.CoroutineExceptionHandler

/** Logs otherwise-unobserved failures from CLI-owned root coroutine scopes. */
internal val CliCoroutineExceptionLogger = CoroutineExceptionHandler { context, failure ->
    CliLogger.error(failure) { "Unhandled CLI coroutine failure in $context" }
}

private val CliLogger by lazy {
    KotlinLogging.logger {}.global()
}
