package io.github.stream29.kodex.cli.app

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fputs
import platform.posix.stderr

@OptIn(ExperimentalForeignApi::class)
internal fun printCliMessage(message: String, error: Boolean) {
    if (error) fputs("$message\n", stderr) else println(message)
}
