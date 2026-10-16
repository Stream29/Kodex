package io.github.stream29.kodex.app.workingdirectory

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.pathpicker.contract.*
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryDependencies
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.*

val workingDirectoryViewModelTest by testSuite {
    test("construction transfers the exact browser without applying a cwd") {
        val browser = CountingBrowser()
        var calls = 0
        val vm = DefaultWorkingDirectoryViewModelFactory.create(browser, WorkingDirectoryDependencies { calls++ })
        assertSame(browser, vm.picker)
        assertTrue(vm.isActive)
        assertEquals(0, calls)
        vm.close()
        vm.close()
        assertEquals(1, browser.closes)
        assertEquals(0, calls)
        assertFailsWith<IllegalStateException> { vm.select(Path("/selected")) }
    }
    test("hands off the unchanged selected path then closes the owned browser") {
        val browser = CountingBrowser()
        val path = Path("/selected")
        var selected: Path? = null
        val vm = createWorkingDirectoryViewModel(browser, WorkingDirectoryDependencies {
            assertEquals(0, browser.closes)
            selected = it
        })
        vm.select(path)
        assertSame(path, selected)
        assertFalse(vm.isActive)
        assertEquals(1, browser.closes)
        vm.close()
        assertEquals(1, browser.closes)
    }
    test("operation failure keeps the child open for retry") {
        val browser = CountingBrowser()
        val failure = IllegalArgumentException("failed")
        var fail = true
        val vm = createWorkingDirectoryViewModel(browser, WorkingDirectoryDependencies {
            if (fail) throw failure
        })
        try {
            assertSame(failure, assertFailsWith<IllegalArgumentException> { vm.select(Path("/selected")) })
            assertTrue(vm.isActive)
            assertEquals(0, browser.closes)
            fail = false
            vm.select(Path("/retry"))
            assertFalse(vm.isActive)
        } finally { vm.close() }
    }
    test("cancellation is propagated without closing an otherwise owned child") {
        val browser = CountingBrowser()
        val cancelled = CancellationException("cancelled")
        val vm = createWorkingDirectoryViewModel(browser, WorkingDirectoryDependencies { throw cancelled })
        try {
            assertSame(cancelled, assertFailsWith<CancellationException> { vm.select(Path("/selected")) })
            assertTrue(vm.isActive)
            assertEquals(0, browser.closes)
        } finally { vm.close() }
    }
    test("dependency can consume the child before dispatch without double closure") {
        val browser = CountingBrowser()
        lateinit var vm: WorkingDirectoryViewModel
        vm = createWorkingDirectoryViewModel(browser, WorkingDirectoryDependencies {
            vm.close()
            assertFalse(vm.isActive)
            assertEquals(1, browser.closes)
        })
        vm.select(Path("/selected"))
        assertEquals(1, browser.closes)
    }
    test("a consumed dependency failure does not reopen the child") {
        val browser = CountingBrowser()
        lateinit var vm: WorkingDirectoryViewModel
        vm = createWorkingDirectoryViewModel(browser, WorkingDirectoryDependencies {
            vm.close()
            error("failed after consumption")
        })
        assertFailsWith<IllegalStateException> { vm.select(Path("/selected")) }
        assertFalse(vm.isActive)
        assertEquals(1, browser.closes)
    }
    test("close during selection does not cancel or roll back the caller operation") {
        runTest {
            val browser = CountingBrowser()
            val started = CompletableDeferred<Path>()
            val finish = CompletableDeferred<Unit>()
            var applied = false
            val vm = createWorkingDirectoryViewModel(browser, WorkingDirectoryDependencies {
                started.complete(it)
                finish.await()
                applied = true
            })
            val operation = launch { vm.select(Path("/selected")) }
            assertEquals(Path("/selected"), started.await())
            vm.close()
            finish.complete(Unit)
            operation.join()
            assertTrue(applied)
            assertFalse(operation.isCancelled)
            assertEquals(1, browser.closes)
        }
    }
}

private class CountingBrowser : DirectoryPickerViewModel {
    override val state = MutableStateFlow(DirectoryPickerState(loadState = DirectoryPickerLoadState.Loading(1, Path("/start"))))
    override val effects = emptyFlow<DirectoryPickerEffect>()
    var closes = 0
    override fun navigateTo(directory: Path) = Unit
    override fun navigateUp() = Unit
    override fun updateFilter(query: String) = Unit
    override fun clearFilter() = Unit
    override fun retry() = Unit
    override fun confirm() = Unit
    override fun close() { closes++ }
}
