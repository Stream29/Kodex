package io.github.stream29.kodex.rpc.client

import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.NoMatchException
import io.github.stream29.kodex.utils.rpcexception.RemoteException
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import io.github.stream29.kodex.utils.rpcexception.SessionNotFound
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.withService
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

@Rpc
internal interface ClientProbe {
    suspend fun echo(value: String?): String?
    suspend fun fail(kind: String)
    suspend fun waitUntilCancelled()
    fun values(): Flow<Int>
    fun failingValues(kind: String, failAtBinding: Boolean): Flow<Int>
    fun heldValues(): Flow<Int>
}

internal fun knownFailure(kind: String): RemoteException = when (kind) {
    "noMatch" -> NoMatchException()
    "inactive" -> SessionNotActive()
    "notFound" -> SessionNotFound()
    "cacheNonce" -> CacheNonceMismatch()
    else -> error("Unexpected known failure: $kind")
}

internal fun assertKnown(kind: String, failure: Throwable) {
    when (kind) {
        "noMatch" -> assertIs<NoMatchException>(failure)
        "inactive" -> assertIs<SessionNotActive>(failure)
        "notFound" -> assertIs<SessionNotFound>(failure)
        "cacheNonce" -> assertIs<CacheNonceMismatch>(failure)
        else -> error("Unexpected known failure: $kind")
    }
}

internal class ClientProbeImpl : ClientProbe {
    val firstValueReceived = CompletableDeferred<Unit>()
    val flowStarted = CompletableDeferred<Unit>()
    val flowReleased = CompletableDeferred<Unit>()
    val callStarted = CompletableDeferred<Unit>()
    val callReleased = CompletableDeferred<Unit>()
    val attempts = MutableStateFlow(0)

    override suspend fun echo(value: String?): String? = value

    override suspend fun fail(kind: String) {
        attempts.update { it + 1 }
        throwFailure(kind)
    }

    private fun throwFailure(kind: String): Nothing = throw when (kind) {
        "noMatch", "inactive", "notFound", "cacheNonce" -> knownFailure(kind)
        "cancel" -> CancellationException(NoMatchException().message)
        else -> Throwable(kind)
    }

    override suspend fun waitUntilCancelled() {
        try {
            callStarted.complete(Unit)
            awaitCancellation()
        } finally {
            callReleased.complete(Unit)
        }
    }

    override fun values(): Flow<Int> = flowOf(1, 2, 3)

    override fun failingValues(kind: String, failAtBinding: Boolean): Flow<Int> {
        if (failAtBinding) {
            attempts.update { it + 1 }
            throwFailure(kind)
        }
        return flow {
            attempts.update { it + 1 }
            try {
                emit(1)
                // Keep the terminal failure from overtaking the first delivered element.
                firstValueReceived.await()
                throwFailure(kind)
            } finally {
                flowReleased.complete(Unit)
            }
        }
    }

    override fun heldValues(): Flow<Int> = flow {
        try {
            flowStarted.complete(Unit)
            emit(1)
            awaitCancellation()
        } finally {
            flowReleased.complete(Unit)
        }
    }
}

internal suspend fun withProbe(
    block: suspend CoroutineScope.(ClientProbe, ClientProbeImpl) -> Unit,
) {
    withTimeout(15.seconds) {
        val backend = ClientProbeImpl()
        withInMemoryRpc(
            registerServices = { registerService(ClientProbe::class) { backend } },
        ) { raw ->
            block(RestoringRpcClient(raw).withService<ClientProbe>(), backend)
        }
    }
}
