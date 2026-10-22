package io.github.stream29.kodex.utils.osenvironment

import kotlinx.io.files.Path

/**
 * @return Nullable because environment variables may be unset; `null` means the
 * requested variable is absent on the current host.
 */
public expect fun environmentVariable(name: String): String?

/**
 * Detects the home path without checking existence or readability.
 *
 * @return `null` when no user home directory could be detected.
 */
public expect fun userHomeDirectory(): Path?

/** Numeric identifier of the current host process. */
public expect fun processId(): Long

/**
 * Requires a detected user home path, without creating or probing it.
 *
 * @throws IllegalStateException when [userHomeDirectory] cannot detect a home path.
 */
public expect fun requireUserHomeDirectory(): Path
