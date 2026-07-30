package io.github.stream29.kodex.utils.osenvironment

import kotlinx.io.files.Path

public actual fun environmentVariable(name: String): String? =
    System.getenv(name)

public actual fun userHomeDirectory(): Path? =
    System.getProperty("user.home")
        ?.takeIf(String::isNotBlank)
        ?.let(::Path)
        ?: userHomeDirectoryFromEnvironment()

public actual fun processId(): Long =
    ProcessHandle.current().pid()
