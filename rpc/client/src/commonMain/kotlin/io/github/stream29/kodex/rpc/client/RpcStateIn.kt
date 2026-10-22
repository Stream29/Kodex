package io.github.stream29.kodex.rpc.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

public actual suspend fun <T> CoroutineScope.rpcStateIn(
    get: suspend () -> T,
    getFlow: () -> Flow<T>,
): StateFlow<T> {
    val initialValue = get()
    return getFlow().stateIn(this, SharingStarted.Eagerly, initialValue)
}
