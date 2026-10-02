package io.github.stream29.kodex.cli.workingdirectory

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.pathpicker.contract.*
import io.github.stream29.kodex.app.workingdirectory.createWorkingDirectoryViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryDependencies
import io.github.stream29.kodex.cli.components.TuiPopupHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.test.*

val workingDirectoryPopupTest by testSuite {
    val path = Path("/browser")
    val cases = listOf(
        DirectoryPickerLoadState.Loading(1, path) to "Loading directories",
        DirectoryPickerLoadState.Ready(1, path, path, listOf(Path(path, "Alpha"))) to "Alpha/",
        DirectoryPickerLoadState.Failed(1, path, DirectoryPickerFailure.FileSystem("safe failure")) to "safe failure",
    )
    for ((load, message) in cases) {
        test("renders $message from the exact browser state with one disposal owner") {
            val browser = RenderBrowser(load)
            var selections = 0
            val vm = createWorkingDirectoryViewModel(browser, WorkingDirectoryDependencies { selections++ })
            var visible by mutableStateOf(true)
            try {
                runMosaicTest {
                    val snapshot = setContentAndSnapshot {
                        Box {
                            TuiPopupHost(modifier = Modifier.width(90).height(24)) {
                                if (visible) WorkingDirectoryPopup(vm, {}, {})
                            }
                        }
                    }
                    assertTrue("Select directory" in snapshot, snapshot)
                    assertTrue(message in snapshot, snapshot)
                    assertEquals(0, selections)
                    visible = false
                    repeat(5) { if (vm.isActive) awaitSnapshot() }
                    assertFalse(vm.isActive)
                    assertEquals(1, browser.closes)
                }
            } finally { vm.close() }
        }
    }
    test("confirmed selection reaches the bound operation then dismisses its host") {
        val browser = RenderBrowser(DirectoryPickerLoadState.Loading(1, path))
        val selected = CompletableDeferred<Path>()
        val vm = createWorkingDirectoryViewModel(browser, WorkingDirectoryDependencies { selected.complete(it) })
        var visible by mutableStateOf(true)
        var completions = 0
        try {
            runMosaicTest {
                setContentAndSnapshot {
                    Box {
                        TuiPopupHost(modifier = Modifier.width(90).height(24)) {
                            if (visible) WorkingDirectoryPopup(
                                vm,
                                onDismissRequest = { error("Selection is not a cancellation") },
                                onSelected = { completions++; visible = false },
                            )
                        }
                    }
                }
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                awaitSnapshot()
                assertEquals(path, withTimeout(1_000) { selected.await() })
                repeat(5) { if (visible) awaitSnapshot() }
                assertFalse(visible)
                assertEquals(1, completions)
                assertEquals(1, browser.confirmations)
                assertEquals(1, browser.closes)
            }
        } finally { vm.close() }
    }
    test("Escape clears a filter first and dismissal never updates cwd") {
        val browser = RenderBrowser(cases[1].first)
        var selections = 0
        var dismissals = 0
        var visible by mutableStateOf(true)
        val vm = createWorkingDirectoryViewModel(browser, WorkingDirectoryDependencies { selections++ })
        try {
            runMosaicTest {
                setContentAndSnapshot {
                    Box {
                        TuiPopupHost(modifier = Modifier.width(90).height(24)) {
                            if (visible) WorkingDirectoryPopup(vm, { dismissals++; visible = false }, {})
                        }
                    }
                }
                sendKeyEvent(KeyboardEvent(codepoint = 'A'.code))
                awaitSnapshotWith("Filter: A")
                assertEquals("A", browser.state.value.filterQuery)
                sendKeyEvent(KeyboardEvent(codepoint = 27))
                awaitSnapshotWith("Filter: type letters")
                assertEquals("", browser.state.value.filterQuery)
                assertEquals(0, dismissals)
                sendKeyEvent(KeyboardEvent(codepoint = 27))
                repeat(5) { if (vm.isActive) awaitSnapshot() }
                assertEquals(1, dismissals)
                assertEquals(0, selections)
                assertEquals(1, browser.closes)
            }
        } finally { vm.close() }
    }
    test("a replaced chooser cannot deliver late selection completion to its replacement") {
        val oldBrowser = RenderBrowser(cases[1].first)
        val nextBrowser = RenderBrowser(cases[1].first)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val old = createWorkingDirectoryViewModel(oldBrowser, WorkingDirectoryDependencies {
            started.complete(Unit)
            finish.await()
            completed.complete(Unit)
        })
        val next = createWorkingDirectoryViewModel(nextBrowser, WorkingDirectoryDependencies {
            error("The replacement did not select anything")
        })
        var current by mutableStateOf(old)
        var drawToken by mutableStateOf(0)
        var callbacks = 0
        try {
            runMosaicTest {
                setContentAndSnapshot {
                    Box {
                        TuiPopupHost(modifier = Modifier.width(90).height(24)) {
                            Text("draw=$drawToken")
                            WorkingDirectoryPopup(current, {}, { callbacks++ })
                        }
                    }
                }
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                drawToken++
                awaitSnapshot()
                withTimeout(1_000) { started.await() }
                current = next
                repeat(5) { if (old.isActive) awaitSnapshot() }
                assertFalse(old.isActive)
                finish.complete(Unit)
                withTimeout(1_000) { completed.await() }
                assertEquals(0, callbacks)
                assertEquals(1, oldBrowser.closes)
                assertTrue(next.isActive)
            }
        } finally {
            finish.complete(Unit)
            old.close()
            next.close()
        }
    }
}

private suspend fun TestMosaic<String>.awaitSnapshotWith(expected: String): String {
    repeat(8) {
        val snapshot = awaitSnapshot()
        if (expected in snapshot) return snapshot
    }
    error("Expected rendered text: $expected")
}

private class RenderBrowser(load: DirectoryPickerLoadState) : DirectoryPickerViewModel {
    override val state = MutableStateFlow(DirectoryPickerState(loadState = load))
    private val output = Channel<DirectoryPickerEffect>(Channel.BUFFERED)
    override val effects = output.receiveAsFlow()
    var closes = 0
    var confirmations = 0
    override fun navigateTo(directory: Path) = Unit
    override fun navigateUp() = Unit
    override fun retry() = Unit
    override fun updateFilter(query: String) { state.value = state.value.copy(filterQuery = query) }
    override fun clearFilter() { updateFilter("") }
    override fun confirm() {
        confirmations++
        output.trySend(DirectoryPickerEffect.DirectorySelected(state.value.currentDirectory))
    }
    override fun close() {
        closes++
        output.close()
    }
}
