package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.rpc.models.OAuthAuthorization
import io.github.stream29.kodex.rpc.models.OAuthLoginEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow

internal class ObservedOAuthOperation(val authorization: OAuthAuthorization, val result: Deferred<Result<Unit>>) {
    suspend fun awaitCommitted() = result.await().getOrThrow()
}

internal suspend fun CoroutineScope.observeOAuth(events: Flow<OAuthLoginEvent>): ObservedOAuthOperation {
    val ready = CompletableDeferred<OAuthAuthorization>()
    val result = async {
        runCatching {
            var committed = false
            events.collect { event ->
                when (event) {
                    is OAuthLoginEvent.Authorization -> check(ready.complete(event.authorization))
                    OAuthLoginEvent.Committed -> {
                        check(ready.isCompleted && !committed)
                        committed = true
                    }
                }
            }
            check(committed) { "OAuth stream ended without a credential commit." }
        }.onFailure { ready.completeExceptionally(it) }
    }
    try {
        return ObservedOAuthOperation(ready.await(), result)
    } catch (failure: Throwable) {
        result.cancel()
        throw failure
    }
}
