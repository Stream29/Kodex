package io.github.stream29.codex.lite.utils.hosttest

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
public actual fun environmentVariable(name: String): String? =
    getenv(name)?.toKString()
