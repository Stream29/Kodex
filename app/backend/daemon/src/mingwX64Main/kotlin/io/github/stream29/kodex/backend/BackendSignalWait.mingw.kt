@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.backend

import io.github.stream29.kodex.backend.cinterop.kodex_backend_register_signal_wait
import io.github.stream29.kodex.backend.cinterop.kodex_backend_unregister_signal_wait
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlin.coroutines.EmptyCoroutineContext

private fun notifyBackendStopSignal(context: COpaquePointer?) {
    val signal = checkNotNull(context).asStableRef<CompletableDeferred<Unit>>().get()
    // Do not resume an Unconfined waiter on its own Win32 callback stack: teardown
    // waits for this callback to return before releasing the StableRef.
    Dispatchers.Default.dispatch(EmptyCoroutineContext, Runnable { signal.complete(Unit) })
}

internal actual suspend fun awaitBackendStopSignal() {
    val signal = CompletableDeferred<Unit>()
    val target = StableRef.create(signal)
    val wait = kodex_backend_register_signal_wait(staticCFunction(::notifyBackendStopSignal), target.asCPointer())
    if (wait == null) {
        target.dispose()
        error("Cannot register the backend stop signal waiter.")
    }
    AutoCloseable {
        val outcome = kodex_backend_unregister_signal_wait(wait)
        if (outcome >= 0) target.dispose()
        check(outcome == 0) { "Cannot release the backend stop signal waiter." }
    }.use { signal.await() }
}
