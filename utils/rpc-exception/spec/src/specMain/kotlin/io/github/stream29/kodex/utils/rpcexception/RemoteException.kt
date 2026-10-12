package io.github.stream29.kodex.utils.rpcexception

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The closed set of known remote failures, restored by [restoreRemoteException].
 *
 * [message] carries this value encoded with the base serializer, including its type discriminator.
 * It has no backing field and is not serialized, avoiding recursive encoding.
 * Cancellation and unknown failures do not belong to this hierarchy.
 */
@Serializable
public sealed class RemoteException : Exception() {
    final override val message: String
        get() = Json.encodeToString<RemoteException>(this)
}
