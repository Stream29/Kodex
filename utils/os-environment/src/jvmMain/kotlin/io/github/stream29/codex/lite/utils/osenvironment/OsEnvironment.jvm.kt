package io.github.stream29.codex.lite.utils.osenvironment

import kotlinx.io.files.Path

public actual fun environmentVariable(name: String): String? =
    System.getenv(name)

public actual fun userHomeDirectory(): Path? =
    System.getProperty("user.home")
        ?.takeIf(String::isNotBlank)
        ?.let(::Path)
        ?: userHomeDirectoryFromEnvironment()
