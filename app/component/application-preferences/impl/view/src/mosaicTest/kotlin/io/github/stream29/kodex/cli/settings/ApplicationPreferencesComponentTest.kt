package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.applicationpreferences.*
import io.github.stream29.kodex.cli.components.TuiPopupHost
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val applicationPreferencesComponentTest by testSuite {
    test("full General renderer orders all four fields and shows widths and canonical paired keys") {
        val deps = PreferencesRenderPorts()
        withPreferencesRenderer(deps) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(30)) { ApplicationPreferencesComponent(vm) }
                }
                var previous = -1
                for (label in listOf("Sidebars", "Left sidebar width", "Right sidebar width",
                    "Input", "New line key", "Submit key")) {
                    val next = snapshot.indexOf(label)
                    assertTrue(next > previous, snapshot)
                    previous = next
                }
                assertTrue("28 columns" in snapshot, snapshot)
                assertTrue("24 columns" in snapshot, snapshot)
                assertTrue("Left sidebar width [-][+]" in snapshot, snapshot)
                assertTrue("New line key [Shift+Enter]" in snapshot, snapshot)
                assertTrue("Submit key [Enter]" in snapshot, snapshot)
                assertTrue(deps.resizes.isEmpty())
                assertTrue(deps.keys.isEmpty())
            }
        }
    }
    test("width mouse and keyboard plus minus apply displayed columns without persisting") {
        val deps = PreferencesRenderPorts()
        withPreferencesRenderer(deps) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(30)) { ApplicationPreferencesComponent(vm) }
                }
                clickPreferencesText(snapshot, "Left sidebar width [-]", offset = "Left sidebar width [".length)
                preferencesSnapshot("27 columns")
                assertEquals(27 to 24, deps.resizes.single())
                // Mouse focus stays on Minus; Tab reaches Plus, Enter invokes the new displayed 27+1.
                sendKeyEvent(KeyboardEvent(codepoint = 9))
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                preferencesSnapshot("28 columns")
                assertEquals(28 to 24, deps.resizes.last())
                clickPreferencesText(preferencesSnapshot("Right sidebar width"), "Right sidebar width [-][+]",
                    offset = "Right sidebar width [-][".length)
                preferencesSnapshot("25 columns")
                assertEquals(28 to 25, deps.resizes.last())
                assertTrue(deps.keys.isEmpty())
            }
        }
    }
    test("minimum minus and maximum plus are disabled and projection never silently resizes") {
        val deps = PreferencesRenderPorts()
        deps.widths.value = 0 to Int.MAX_VALUE
        withPreferencesRenderer(deps) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(30)) { ApplicationPreferencesComponent(vm) }
                }
                assertTrue("4 columns" in snapshot, snapshot)
                assertTrue("${Int.MAX_VALUE} columns" in snapshot, snapshot)
                clickPreferencesText(snapshot, "Left sidebar width [-]", offset = "Left sidebar width [".length)
                clickPreferencesText(preferencesSnapshot("Right sidebar width"), "Right sidebar width [-][+]",
                    offset = "Right sidebar width [-][".length)
                assertTrue(deps.resizes.isEmpty())
                assertEquals(0 to Int.MAX_VALUE, deps.widths.value)
                clickPreferencesText(preferencesSnapshot("Left sidebar width"), "Left sidebar width [-][+]",
                    offset = "Left sidebar width [-][".length)
                preferencesSnapshot("5 columns")
                assertEquals(5 to Int.MAX_VALUE, deps.widths.value)
            }
        }
    }
    test("both complete key menus keyboard selection map to one canonical write and Escape cancels") {
        val deps = PreferencesRenderPorts()
        withPreferencesRenderer(deps) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(30)) { ApplicationPreferencesComponent(vm) }
                }
                clickPreferencesText(snapshot, "New line key [Shift+Enter]", offset = "New line key [".length)
                preferencesSnapshot("[Enter")
                sendKeyEvent(KeyboardEvent(codepoint = 57353))
                preferencesSnapshot("[Enter")
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                preferencesSnapshot("Submit key [Ctrl+Enter]")
                assertEquals(listOf(NewLineKey.Enter), deps.keys)
                clickPreferencesText(preferencesSnapshot("Submit key"), "Submit key [Ctrl+Enter]",
                    offset = "Submit key [".length)
                val menu = preferencesSnapshot("[Enter")
                assertTrue("[Ctrl+Enter]" in menu, menu)
                sendKeyEvent(KeyboardEvent(codepoint = 57352))
                preferencesSnapshot("[Enter")
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                preferencesSnapshot("New line key [Shift+Enter]")
                assertEquals(listOf(NewLineKey.Enter, NewLineKey.ShiftEnter), deps.keys)
                clickPreferencesText(preferencesSnapshot("New line key"), "New line key [Shift+Enter]",
                    offset = "New line key [".length)
                preferencesSnapshot("[Enter")
                sendKeyEvent(KeyboardEvent(codepoint = 27))
                preferencesSnapshot("Sidebars")
                assertEquals(2, deps.keys.size)
                assertTrue(deps.resizes.isEmpty())
            }
        }
    }
    test("shared failure acknowledgement suppression and closed branch do not reset preferences") {
        val deps = PreferencesRenderPorts()
        deps.operationFailure.value = true
        withPreferencesRenderer(deps) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(30)) { ApplicationPreferencesComponent(vm) }
                }
                assertTrue("A settings operation failed." in snapshot, snapshot)
                clickPreferencesText(snapshot, "[Dismiss]")
                assertFalse("A settings operation failed." in preferencesSnapshot("Sidebars"))
                vm.close()
                assertFalse("Sidebars" in preferencesSnapshot(""))
                assertEquals(28 to 24, deps.widths.value)
            }
        }
        deps.operationFailure.value = true
        withPreferencesRenderer(deps) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(30)) {
                        ApplicationPreferencesComponent(vm, showOperationFailure = false)
                    }
                }
                assertFalse("A settings operation failed." in snapshot, snapshot)
                assertTrue(vm.state.value.operationFailure)
            }
        }
    }
}

private suspend fun withPreferencesRenderer(
    deps: PreferencesRenderPorts, action: suspend (ApplicationPreferencesViewModel) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val vm = createApplicationPreferencesViewModel(deps, scope)
    try { action(vm) } finally { vm.close(); scope.cancel() }
}
private suspend fun TestMosaic<String>.preferencesSnapshot(expected: String): String {
    var latest = ""
    repeat(5) {
        latest = try { awaitSnapshot() } catch (_: TimeoutCancellationException) {
            draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
        }
        if (expected in latest) return latest
    }
    assertTrue(expected in latest, latest)
    return latest
}
private suspend fun TestMosaic<String>.clickPreferencesText(snapshot: String, text: String, offset: Int = 1) {
    val lines = snapshot.lines()
    val row = lines.indexOfFirst { text in it }
    assertTrue(row >= 0, snapshot)
    val column = lines[row].indexOf(text) + offset
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Press, MouseEvent.Button.Left))
    preferencesSnapshot("")
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Release))
    preferencesSnapshot("")
}
private class PreferencesRenderPorts : ApplicationPreferencesDependencies {
    override val widths = MutableStateFlow(28 to 24)
    override val newLineKey = MutableStateFlow(NewLineKey.ShiftEnter)
    override val operationFailure = MutableStateFlow(false)
    val resizes = mutableListOf<Pair<Int, Int>>()
    val keys = mutableListOf<NewLineKey>()
    override fun setLeftWidth(columns: Int) {
        widths.value = columns to widths.value.second
        resizes += widths.value
    }
    override fun setRightWidth(columns: Int) {
        widths.value = widths.value.first to columns
        resizes += widths.value
    }
    override fun setNewLineKey(newLineKey: NewLineKey): PreferencesWriteAdmission {
        keys += newLineKey
        this.newLineKey.value = newLineKey
        return PreferencesWriteAdmission.Accepted
    }
    override fun reportFailure(failure: Throwable) { operationFailure.value = true }
    override fun dismissFailure() { operationFailure.value = false }
}
