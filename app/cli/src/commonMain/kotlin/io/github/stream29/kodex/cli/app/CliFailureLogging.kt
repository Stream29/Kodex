package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.backend.host.discoverReadyBackend
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.logging.writeCliTerminalFailure
import io.github.stream29.kodex.utils.osenvironment.processId
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path

internal fun logCliFailure(failure: Throwable, home: Path): Unit =
    runBlocking {
        // A parse/connection error says nothing about the default Home's generation.
        // Discovery preflights the path and only trusts a ready backend that prepared it.
        if (discoverReadyBackend(home) != null) writeCliTerminalFailure(resolveCliLoggingHome(home), processId(), failure)
    }

internal suspend fun resolveCliLoggingHome(home: Path): Path {
    val fs = SystemCoroutineFileSystem
    val resolved = fs.resolve(home)
    val logDirectory = Path(resolved, "log")
    val existing = fs.metadataOrNull(logDirectory)
    check(if (existing == null) logDirectory !in fs.list(resolved)
        else existing.isDirectory && fs.resolve(logDirectory) == logDirectory
    ) { "The Kodex log directory is not a direct directory: $logDirectory" }
    return resolved
}
