package io.github.stream29.kodex.utils.rpcexception

import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * Restores a known remote exception from a JSON message, preserving all other failures.
 *
 * Decodes the closed [RemoteException] hierarchy without a caller-supplied type.
 * Cancellation bypasses decoding. Invalid JSON and unknown types preserve the original throwable.
 *
 * Inline [block] may suspend in a suspending caller. Wrap the RPC operation, not downstream
 * consumers: returning a Flow does not collect it or catch failures from later collection.
 */
public inline fun <R> restoreRemoteException(block: () -> R): R {
    try {
        return block()
    } catch (original: Throwable) {
        if (original is CancellationException) throw original
        val message = original.message
        if (message == null || !message.startsWith('{')) throw original
        val restored = try {
            Json.decodeFromString<RemoteException>(message)
        } catch (_: IllegalArgumentException) {
            throw original
        }
        throw restored
    }
}
