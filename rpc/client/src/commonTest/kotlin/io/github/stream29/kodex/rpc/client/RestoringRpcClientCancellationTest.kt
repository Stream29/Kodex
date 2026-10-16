package io.github.stream29.kodex.rpc.client

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlinx.rpc.withService
import kotlin.test.assertTrue

val restoringRpcClientCancellationTest by testSuite {
    test("cancelled subscriber cannot fail its live owner through the upstream buffer child") {
        runTest {
            val entered = CompletableDeferred<Unit>()
            val lateFailure = IllegalStateException("RpcClient was cancelled")
            val raw = object : RpcClient {
                override suspend fun <T> call(call: RpcCall): T = error("Not used")

                override fun <T> callServerStreaming(call: RpcCall): Flow<T> = flow<T> {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        // The upstream kRPC collector can throw after its Job was cancelled.
                        // Cancellation must win before buffer's child can fail its parent.
                        throw lateFailure
                    }
                }
            }
            val parent = SupervisorJob(coroutineContext[Job])
            val owner = Job(parent)
            val reported = mutableListOf<Throwable>()
            val scope = CoroutineScope(
                coroutineContext + owner + CoroutineExceptionHandler { _, failure ->
                    reported += failure
                },
            )
            try {
                val proxy = RestoringRpcClient(raw).withService<ClientProbe>()
                val subscriber = scope.launch { proxy.heldValues().collect() }
                entered.await()
                subscriber.cancelAndJoin()
                assertTrue(owner.isActive, "A cancelled subscription must not invalidate its live owner.")
                assertTrue(reported.isEmpty(), "No late non-cancellation failure may escape: $reported")
            } finally {
                parent.cancelAndJoin()
            }
        }
    }
}
