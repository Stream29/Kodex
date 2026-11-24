@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.backend

import io.github.stream29.kodex.backend.cinterop.kodex_backend_cancel_signal_wait
import io.github.stream29.kodex.backend.cinterop.kodex_backend_wait_signal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
private val signalWaitDispatcher = Dispatchers.IO.limitedParallelism(Int.MAX_VALUE, "Kodex.BackendSignalIO")

internal actual suspend fun awaitBackendStopSignal(): Unit = coroutineScope {
    val wait = async(signalWaitDispatcher) {
        check(kodex_backend_wait_signal() >= 0) { "Cannot wait for a backend stop signal." }
    }
    try {
        wait.await()
    } finally {
        if (!wait.isCompleted) {
            check(kodex_backend_cancel_signal_wait() == 0) { "Cannot wake the backend stop signal waiter." }
            withContext(NonCancellable) { wait.join() }
        }
    }
}
