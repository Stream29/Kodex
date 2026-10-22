package io.github.stream29.kodex.utils.osenvironment

import kotlinx.io.files.Path

public actual fun requireUserHomeDirectory(): Path =
    userHomeDirectory() ?: throw IllegalStateException("User home directory was not found.")

internal fun userHomeDirectoryFromEnvironment(): Path? {
    val userProfile = environmentVariable("USERPROFILE")?.takeIf(String::isNotBlank)
    val home = environmentVariable("HOME")?.takeIf(String::isNotBlank)
    val homeDrive = environmentVariable("HOMEDRIVE")?.takeIf(String::isNotBlank)
    val homePath = environmentVariable("HOMEPATH")?.takeIf(String::isNotBlank)
    val driveHome = if (homeDrive != null && homePath != null) homeDrive + homePath else null
    return (userProfile ?: home ?: driveHome)?.let(::Path)
}
