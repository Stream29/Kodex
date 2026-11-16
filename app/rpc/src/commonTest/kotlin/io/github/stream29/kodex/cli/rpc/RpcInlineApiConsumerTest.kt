package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.rpc.client.asSuspendMutableStateFlow
import io.github.stream29.kodex.rpc.client.update as rpcUpdate
import io.github.stream29.kodex.utils.rpcexception.NoMatchException
import io.github.stream29.kodex.utils.rpcexception.restoreRemoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

private suspend fun nonLocalUpdate(state: io.github.stream29.kodex.rpc.contract.SuspendMutableStateFlow<Int>): Int {
    state.rpcUpdate {
        yield()
        return 17
    }
    return -1
}

private fun nonLocalRestore(): Int {
    restoreRemoteException { return 23 }
}

val rpcInlineApiConsumerTest by testSuite {
    test("CAS inline update supports suspension and commits the transformed value") {
        runTest {
            val source = MutableStateFlow(1)
            val state = source.asSuspendMutableStateFlow { expect, update ->
                if (source.value != expect) false else {
                    source.value = update
                    true
                }
            }

            assertEquals(Unit, state.rpcUpdate { yield(); it + 1 })
            assertEquals(2, source.value)
        }
    }

    test("CAS inline transform preserves non-local return without committing") {
        runTest {
            val source = MutableStateFlow(3)
            val state = source.asSuspendMutableStateFlow { expect, update ->
                if (source.value != expect) false else {
                    source.value = update
                    true
                }
            }

            assertEquals(17, nonLocalUpdate(state))
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
