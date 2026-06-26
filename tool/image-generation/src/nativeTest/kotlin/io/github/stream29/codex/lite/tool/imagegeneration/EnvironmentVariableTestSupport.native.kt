package io.github.stream29.codex.lite.tool.imagegeneration

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun environmentVariable(name: String): String? =
    getenv(name)?.toKString()
