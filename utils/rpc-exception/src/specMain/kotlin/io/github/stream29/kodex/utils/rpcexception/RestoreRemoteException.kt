package io.github.stream29.kodex.utils.rpcexception

/**
 * Restores a known remote exception from a JSON message, preserving all other failures.
 *
 * Decodes the closed [RemoteException] hierarchy without a caller-supplied type.
 * Cancellation bypasses decoding. Invalid JSON and unknown types preserve the original throwable.
 *
 * Inline [block] may suspend in a suspending caller. Wrap the RPC operation, not downstream
 * consumers: returning a Flow does not collect it or catch failures from later collection.
 */
public expect inline fun <R> restoreRemoteException(block: () -> R): R
