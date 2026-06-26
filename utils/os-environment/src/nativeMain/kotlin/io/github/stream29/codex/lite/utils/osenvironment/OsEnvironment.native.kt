package io.github.stream29.codex.lite.utils.osenvironment

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
public actual fun environmentVariable(name: String): String? =
    getenv(name)?.toKString()

public actual fun userHomeDirectory(): Path? =
    userHomeDirectoryFromEnvironment()
