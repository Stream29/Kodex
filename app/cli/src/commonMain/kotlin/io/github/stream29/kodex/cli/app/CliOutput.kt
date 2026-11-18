package io.github.stream29.kodex.cli.app

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.readByteArray
import platform.posix.ferror
import platform.posix.fputs
import platform.posix.fread
import platform.posix.stderr
import platform.posix.stdin

@OptIn(ExperimentalForeignApi::class)
internal fun printCliMessage(message: String, error: Boolean) {
    if (error) fputs("$message\n", stderr) else println(message)
}

@OptIn(ExperimentalForeignApi::class)
internal fun readCliStdinText(): String = readCliStdin().readString()

internal fun readCliStdinTextStrict(): String = readCliStdin().readByteArray().decodeToString(throwOnInvalidSequence = true)

@OptIn(ExperimentalForeignApi::class)
private fun readCliStdin(): Buffer = memScoped {
    val chunkSize = 8192
    val buffer = allocArray<ByteVar>(chunkSize)
    val out = Buffer()
    while (true) {
        val readCount = fread(buffer, 1u, chunkSize.convert(), stdin).toInt()
        if (readCount > 0) {
            out.write(buffer.readBytes(readCount))
        }
        if (readCount < chunkSize) {
            if (ferror(stdin) != 0) error("Failed to read from standard input.")
            break
        }
    }
    out
}
