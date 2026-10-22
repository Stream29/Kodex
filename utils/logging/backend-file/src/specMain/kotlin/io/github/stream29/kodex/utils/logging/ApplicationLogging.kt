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
public expect suspend fun initializeLogging(kodexHome: Path, processName: String = "Kodex"): Unit
