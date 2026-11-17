package io.github.stream29.kodex.app.sessiondelete

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteDependencies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

val sessionDeleteViewModelTest by testSuite {
    for (result in listOf(true, false)) {
        test("captures index and title and returns $result without self-dismissal") {
            val calls = mutableListOf<Int>()
            val vm = createSessionDeleteViewModel(41, "captured", SessionDeleteDependencies {
                calls += it
                result
            })
            try {
                assertTrue(calls.isEmpty())
                assertEquals(41, vm.sessionIndex)
                assertEquals("captured", vm.threadName)
                assertEquals(result, vm.delete())
                assertEquals(listOf(41), calls)
                assertTrue(vm.isActive)
            } finally {
                vm.close()
            }
        }
    }
    test("failure propagates without synthesizing false or closing the confirmation") {
        val failure = IllegalArgumentException("operation failed")
        val vm = createSessionDeleteViewModel(41, null, SessionDeleteDependencies { throw failure })
        try {
            assertSame(failure, assertFailsWith<IllegalArgumentException> { vm.delete() })
            assertTrue(vm.isActive)
        } finally {
            vm.close()
        }
    }
    test("cancellation propagates without synthesizing false") {
        val failure = CancellationException("cancelled")
        val vm = createSessionDeleteViewModel(41, null, SessionDeleteDependencies { throw failure })
        try {
            assertSame(failure, assertFailsWith<CancellationException> { vm.delete() })
        } finally {
            vm.close()
        }
    }
    test("close never deletes and rejects later submissions") {
        var calls = 0
        val vm = createSessionDeleteViewModel(41, null, SessionDeleteDependencies { calls++; true })
        vm.close()
        vm.close()
        assertFalse(vm.isActive)
        assertFailsWith<IllegalStateException> { vm.delete() }
        assertEquals(0, calls)
    }
    test("close does not roll back an already dispatched deletion") {
        runTest {
            val started = CompletableDeferred<Int>()
            val finish = CompletableDeferred<Boolean>()
            val vm = createSessionDeleteViewModel(41, null, SessionDeleteDependencies {
                started.complete(it)
                finish.await()
            })
            var result: Boolean? = null
            val operation = launch { result = vm.delete() }
            assertEquals(41, started.await())
            vm.close()
            finish.complete(true)
            operation.join()
            assertEquals(true, result)
        }
    }
}
