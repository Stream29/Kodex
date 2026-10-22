package io.github.stream29.kodex.utils.rpcexception

import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

public actual inline fun <R> restoreRemoteException(block: () -> R): R {
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
