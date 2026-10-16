package io.github.stream29.kodex.rpc.server

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Release every acquired resource without replacing the operation's primary failure. */
internal suspend fun closeBackendResources(
    primary: Throwable?,
    vararg releases: suspend () -> Unit,
) {
    var failure = primary
    withContext(NonCancellable) {
        for (release in releases) {
            try {
                release()
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup
                else if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
    }
    if (primary == null) failure?.let { throw it }
}
