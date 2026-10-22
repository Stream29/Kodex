package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.rpc.client.asSuspendMutableStateFlow
import io.github.stream29.kodex.rpc.client.getAndUpdate as rpcGetAndUpdate
import io.github.stream29.kodex.rpc.client.update as rpcUpdate
import io.github.stream29.kodex.rpc.client.updateAndGet as rpcUpdateAndGet
import io.github.stream29.kodex.utils.rpcexception.NoMatchException
import io.github.stream29.kodex.utils.rpcexception.restoreRemoteException
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertFailsWith

private suspend fun nonLocalUpdate(state: io.github.stream29.kodex.rpc.contract.SuspendMutableStateFlow<Int>): Int {
    state.rpcUpdate {
        yield()
        return 17
    }
    return -1
}

private suspend fun nonLocalGetAndUpdate(state: io.github.stream29.kodex.rpc.contract.SuspendMutableStateFlow<Int>): Int {
    state.rpcGetAndUpdate {
        yield()
        return 18
    }
    return -1
}

private suspend fun nonLocalUpdateAndGet(state: io.github.stream29.kodex.rpc.contract.SuspendMutableStateFlow<Int>): Int {
    state.rpcUpdateAndGet {
        yield()
        return 19
    }
    return -1
}

private fun nonLocalRestore(): Int {
    restoreRemoteException { return 23 }
}

val rpcInlineApiConsumerTest by testSuite {
    test("CAS inline APIs support suspension and retain their distinct results") {
        runTest {
            val source = MutableStateFlow(1)
            val state = source.asSuspendMutableStateFlow { expect, update ->
                if (source.value != expect) false else {
                    source.value = update
                    true
                }
            }

            assertEquals(Unit, state.rpcUpdate { yield(); it + 1 })
            assertEquals(2, state.rpcGetAndUpdate { yield(); it + 1 })
            assertEquals(4, state.rpcUpdateAndGet { yield(); it + 1 })
            assertEquals(4, source.value)
        }
    }

    test("all CAS inline transforms preserve non-local return without committing") {
        runTest {
            val source = MutableStateFlow(3)
            val state = source.asSuspendMutableStateFlow { expect, update ->
                if (source.value != expect) false else {
                    source.value = update
                    true
                }
            }

            assertEquals(17, nonLocalUpdate(state))
            assertEquals(18, nonLocalGetAndUpdate(state))
            assertEquals(19, nonLocalUpdateAndGet(state))
            assertEquals(3, source.value)
        }
    }

    test("restoreRemoteException preserves success cancellation known failures and bad JSON") {
        runTest {
            assertEquals("success", restoreRemoteException {
                yield()
                "success"
            })

            val cancellation = CancellationException("cancelled")
            assertSame(cancellation, assertFailsWith<CancellationException> {
                restoreRemoteException { throw cancellation }
            })

            val known = NoMatchException()
            val restored = assertFailsWith<NoMatchException> {
                restoreRemoteException { throw Throwable(known.message) }
            }
            assertNotSame(known, restored)

            val malformed = Throwable("{not-json")
            assertSame(malformed, assertFailsWith<Throwable> {
                restoreRemoteException { throw malformed }
            })
        }
    }

    test("restoreRemoteException preserves cross-module non-local return") {
        assertEquals(23, nonLocalRestore())
    }
}
