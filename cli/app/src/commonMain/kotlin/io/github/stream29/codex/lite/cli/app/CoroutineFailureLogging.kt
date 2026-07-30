package io.github.stream29.codex.lite.cli.app

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineExceptionHandler

/** Logs otherwise-unobserved failures from CLI-owned root coroutine scopes. */
internal val CliCoroutineExceptionLogger = CoroutineExceptionHandler { context, failure ->
    KotlinLogging.logger {}.error(failure) { "Unhandled CLI coroutine failure in $context" }
}
