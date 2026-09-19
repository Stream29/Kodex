package io.github.stream29.kodex.rpc.client

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.rpcexception.NoMatchException
import io.github.stream29.kodex.utils.rpcexception.RemoteException
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlinx.rpc.withService
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** Throws at invocation, not collection: real kRPC normally defers remote failures to collection. */
private class ThrowingClient(private val failure: Throwable) : RpcClient {
    var calls = 0
    var streams = 0

    override suspend fun <T> call(call: RpcCall): T {
        calls++
        throw failure
    }

    override fun <T> callServerStreaming(call: RpcCall): Flow<T> {
        streams++
        throw failure
    }
}

val restoringRpcClientDelegationTest by testSuite {
    for (kind in listOf("noMatch", "inactive", "notFound", "cacheNonce")) {
        test("$kind restores immediate delegate failures once for both entry points") {
            val raw = ThrowingClient(Throwable(knownFailure(kind).message))
            val client = RestoringRpcClient(raw)
            val first = client.withService<ClientProbe>()
            val second = client.withService<ClientProbe>()
            assertKnown(kind, assertFailsWith<RemoteException> { first.echo("ignored") })
            assertKnown(kind, assertFailsWith<RemoteException> { second.values() })
            assertEquals(1, raw.calls)
            assertEquals(1, raw.streams)
        }
    }

    test("unknown and cancellation failures retain identity at immediate delegate boundaries") {
        for (failure in listOf(
            IllegalStateException("ordinary"),
            IllegalArgumentException("{"),
            Throwable("""{"type":"unknown.Exception"}"""),
            CancellationException(NoMatchException().message),
        )) {
            val raw = ThrowingClient(failure)
            val rpc = RestoringRpcClient(raw).withService<ClientProbe>()
            assertSame(failure, assertFailsWith<Throwable> { rpc.echo("ignored") })
            assertSame(failure, assertFailsWith<Throwable> { rpc.values() })
            assertEquals(1, raw.calls)
            assertEquals(1, raw.streams)
        }
    }

    test("a proxy created earlier from the raw client is not silently wrapped") {
        val wireFailure = Throwable(NoMatchException().message)
        val raw = ThrowingClient(wireFailure)
        val unprotected = raw.withService<ClientProbe>()
        val protected = RestoringRpcClient(raw).withService<ClientProbe>()
        assertSame(wireFailure, assertFailsWith<Throwable> { unprotected.echo("raw") })
        assertFailsWith<NoMatchException> { protected.echo("wrapped") }
        assertEquals(2, raw.calls)
    }
}
