package io.github.stream29.codex.lite.utils.osenvironment

import kotlinx.io.files.Path

/**
 * @return Nullable because environment variables may be unset; `null` means the
 * requested variable is absent on the current host.
 */
public expect fun environmentVariable(name: String): String?

/**
 * @return Nullable because the host may not expose a readable home directory;
 * `null` means no user home directory could be detected.
 */
public expect fun userHomeDirectory(): Path?

public fun requireUserHomeDirectory(): Path =
    userHomeDirectory() ?: throw IllegalStateException("User home directory was not found.")

internal fun userHomeDirectoryFromEnvironment(): Path? {
    val userProfile = environmentVariable("USERPROFILE").nonBlankOrNull()
    val home = environmentVariable("HOME").nonBlankOrNull()
    val homeDrive = environmentVariable("HOMEDRIVE").nonBlankOrNull()
    val homePath = environmentVariable("HOMEPATH").nonBlankOrNull()
    val driveHome = if (homeDrive != null && homePath != null) homeDrive + homePath else null
    return (userProfile ?: home ?: driveHome)?.let(::Path)
}

private fun String?.nonBlankOrNull(): String? =
    this?.takeIf(String::isNotBlank)
