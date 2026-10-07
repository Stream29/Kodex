@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.applicationpreferences

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

val applicationPreferencesViewModelTest by testSuite {
    test("source below minimum clamps display only and no refresh or construction resize occurs") {
        runTest {
            val deps = PreferencesPorts()
            deps.widths.value = 0 to 3
            val vm = createApplicationPreferencesViewModel(deps, backgroundScope)
            assertEquals(MinimumSidebarWidthColumns, vm.state.value.leftWidth)
            assertEquals(MinimumSidebarWidthColumns, vm.state.value.rightWidth)
            assertEquals(0 to 3, deps.widths.value)
            runCurrent()
            deps.widths.value = Int.MAX_VALUE to 1
            runCurrent()
            assertEquals(Int.MAX_VALUE, vm.state.value.leftWidth)
            assertEquals(MinimumSidebarWidthColumns, vm.state.value.rightWidth)
            assertTrue(deps.resizes.isEmpty())
            assertTrue(deps.keys.isEmpty())
            vm.close()
            val reopened = createApplicationPreferencesViewModel(deps, backgroundScope)
            assertEquals(Int.MAX_VALUE, reopened.state.value.leftWidth)
            assertEquals(Int.MAX_VALUE to 1, deps.widths.value)
            reopened.close()
        }
    }
    test("explicit snapshot widths forward unchanged and adapter preserves current other width") {
        runTest {
            val deps = PreferencesPorts()
            val vm = createApplicationPreferencesViewModel(deps, backgroundScope)
            val displayed = vm.state.value.leftWidth
            // A delayed render callback must send displayed+1, not fresh-left+1.
            deps.widths.value = 100 to 37
            vm.setLeftWidth(displayed + 1)
            assertEquals((displayed + 1) to 37, deps.widths.value)
            deps.widths.value = 59 to 99
            vm.setRightWidth(20)
            assertEquals(59 to 20, deps.widths.value)
            vm.setLeftWidth(0)
            vm.setRightWidth(3)
            assertEquals(0 to 3, deps.widths.value)
            runCurrent()
            assertEquals(MinimumSidebarWidthColumns, vm.state.value.leftWidth)
            assertEquals(MinimumSidebarWidthColumns, vm.state.value.rightWidth)
            vm.setRightWidth(Int.MAX_VALUE)
            assertEquals(0 to Int.MAX_VALUE, deps.widths.value)
            assertTrue(deps.keys.isEmpty())
            vm.close()
        }
    }
    test("negative active width throws but closed invalid command is no-op") {
        runTest {
            val deps = PreferencesPorts()
            val vm = createApplicationPreferencesViewModel(deps, backgroundScope)
            assertFailsWith<IllegalArgumentException> { vm.setLeftWidth(-1) }
            assertFailsWith<IllegalArgumentException> { vm.setRightWidth(Int.MIN_VALUE) }
            assertTrue(deps.failures.isEmpty())
            assertTrue(deps.resizes.isEmpty())
            vm.close()
            vm.setLeftWidth(-1)
            vm.setRightWidth(-1)
            assertTrue(deps.resizes.isEmpty())
        }
    }
    test("canonical paired keys admit once each and drain after close without changing widths") {
        runTest {
            val deps = PreferencesPorts()
            val vm = createApplicationPreferencesViewModel(deps, backgroundScope)
            assertEquals(SubmitKey.Enter, vm.state.value.submitKey)
            vm.setNewLineKey(NewLineKey.Enter)
            vm.setSubmitKey(SubmitKey.Enter)
            vm.setSubmitKey(SubmitKey.CtrlEnter)
            assertEquals(listOf(NewLineKey.Enter, NewLineKey.ShiftEnter, NewLineKey.Enter), deps.keys)
            assertEquals(NewLineKey.ShiftEnter, vm.state.value.newLineKey) // acceptance != receipt
            vm.close()
            deps.drain()
            assertEquals(NewLineKey.Enter, deps.newLineKey.value)
            assertEquals(28 to 24, deps.widths.value)
            assertTrue(deps.resizes.isEmpty())
            val reopened = createApplicationPreferencesViewModel(deps, backgroundScope)
            assertEquals(SubmitKey.CtrlEnter, reopened.state.value.submitKey)
            reopened.close()
        }
    }
    test("queue rejection resize failure and cancellation use one shared failure authority") {
        runTest {
            val deps = PreferencesPorts()
            val vm = createApplicationPreferencesViewModel(deps, backgroundScope)
            deps.admission = PreferencesWriteAdmission.Rejected
            vm.setNewLineKey(NewLineKey.Enter)
            assertEquals(1, deps.failures.size)
            deps.failure = IllegalStateException("private detail")
            vm.setLeftWidth(20)
            assertEquals(2, deps.failures.size)
            val cancelled = CancellationException("cancel")
            deps.failure = cancelled
            assertSame(cancelled, assertFailsWith<CancellationException> { vm.setRightWidth(21) })
            assertSame(cancelled, assertFailsWith<CancellationException> { vm.setSubmitKey(SubmitKey.Enter) })
            assertEquals(2, deps.failures.size)
            runCurrent()
            assertTrue(vm.state.value.operationFailure)
            vm.close()
            val next = createApplicationPreferencesViewModel(deps, backgroundScope)
            assertTrue(next.state.value.operationFailure)
            next.dismissFailure()
            runCurrent()
            assertFalse(next.state.value.operationFailure)
            next.close()
        }
    }
    test("owner cancellation freezes projection and rejects new writes without resetting application") {
        runTest {
            val deps = PreferencesPorts()
            val owner = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val vm = createApplicationPreferencesViewModel(deps, owner)
            vm.setLeftWidth(41)
            vm.setNewLineKey(NewLineKey.Enter)
            runCurrent()
            owner.cancel()
            runCurrent()
            assertTrue(vm.state.value.closed)
            val frozen = vm.state.value
            deps.widths.value = 0 to 0
            vm.setLeftWidth(10)
            vm.setRightWidth(11)
            vm.setNewLineKey(NewLineKey.ShiftEnter)
            vm.dismissFailure()
            runCurrent()
            assertEquals(frozen, vm.state.value)
            assertEquals(1, deps.resizes.size)
            assertEquals(1, deps.keys.size)
            deps.drain()
            assertEquals(NewLineKey.Enter, deps.newLineKey.value)
            assertEquals(0 to 0, deps.widths.value)
            vm.close()
        }
    }
}

private class PreferencesPorts : ApplicationPreferencesDependencies {
    override val widths = MutableStateFlow(28 to 24)
    override val newLineKey = MutableStateFlow(NewLineKey.ShiftEnter)
    override val operationFailure = MutableStateFlow(false)
    val resizes = mutableListOf<Pair<Int, Int>>()
    val keys = mutableListOf<NewLineKey>()
    val failures = mutableListOf<Throwable>()
    var admission = PreferencesWriteAdmission.Accepted
    var failure: Throwable? = null
    override fun setLeftWidth(columns: Int) {
        failure?.let { throw it }
        widths.value = columns to widths.value.second
        resizes += widths.value
    }
    override fun setRightWidth(columns: Int) {
        failure?.let { throw it }
        widths.value = widths.value.first to columns
        resizes += widths.value
    }
    override fun setNewLineKey(newLineKey: NewLineKey): PreferencesWriteAdmission {
        failure?.let { throw it }
        if (admission == PreferencesWriteAdmission.Accepted) keys += newLineKey
        return admission
    }
    override fun reportFailure(failure: Throwable) { failures += failure; operationFailure.value = true }
    override fun dismissFailure() { operationFailure.value = false }
    fun drain() { keys.forEach { newLineKey.value = it } }
}
