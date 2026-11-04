@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.utils.logging

import kotlinx.io.files.Path

/**
 * Configures process-wide file logging before backend application services are created.
 *
 * Writes under `log` in [kodexHome], using [processName] as the file base name.
 * Installs the direct KotlinLogging factory at INFO level, without its startup message,
 * and a rolling writer configured with `rollOnSize = 10 MiB` and `maxLogFiles = 5`.
 *
 * @throws kotlinx.io.IOException if the log directory cannot be created.
 * @throws kotlinx.coroutines.CancellationException if directory creation is cancelled.
 */
public expect suspend fun initializeLogging(kodexHome: Path, processName: String = "Kodex"): ApplicationLoggingSession

/**
 * Writes one CLI terminal failure to a private, uniquely named process log and
 * flushes it before returning, including the throwable's stack and causes.
 */
public expect suspend fun writeCliTerminalFailure(kodexHome: Path, processId: Long, failure: Throwable): Unit

/** Owns the file writer; close drains accepted events for at most two seconds. */
public expect class ApplicationLoggingSession {
    public suspend fun close(): Unit
}
