package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.migration.KodexHomeHandle
import io.github.stream29.kodex.utils.logging.initializeLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.seconds

/** Startup failure occurs before Application takes ownership of the Home handle. */
internal suspend fun initializeCliLogging(
    home: KodexHomeHandle,
    initialize: suspend (Path) -> Unit = ::initializeLogging,
    report: (Throwable) -> Unit = {
        println("Unable to initialize Kodex logging: ${it.message ?: it}")
    },
): Boolean {
    try {
        initialize(home.home)
        return true
    } catch (primary: Throwable) {
        val cleanup = withContext(NonCancellable) {
            withContext(Dispatchers.Default) {
                try {
                    withTimeout(30.seconds) { runCatching { home.closeAndJoin() } }
                } catch (failure: Throwable) {
                    Result.failure<Unit>(failure)
                }
            }
        }.exceptionOrNull()
        if (cleanup != null && cleanup !== primary) primary.addSuppressed(cleanup)
        report(primary)
        return false
    }
}
