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
import io.github.stream29.kodex.app.sessiontitlesettings.*
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val sessionTitleSettingsComponentTest by testSuite {
    test("full renderer preserves section order injected default and custom reasoning display") {
        val deps = TitleRenderPorts()
        deps.settings.value = deps.settings.value.copy(reasoningEffort = ReasoningEffort.Custom("provider-effort"))
        withTitleRenderer(deps) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(30)) { SessionTitleSettingsComponent(vm) }
                }
                var previous = -1
                for (label in listOf("Title generation", "[x] Automatic session title", "Title model", "Title reasoning")) {
                    val next = snapshot.indexOf(label)
                    assertTrue(next > previous, snapshot)
                    previous = next
                }
                assertTrue("injected-render-default" in snapshot, snapshot)
                assertTrue("provider-effort" in snapshot, snapshot)
                assertTrue("Reasoning effort used to generate automatic session titles." in snapshot, snapshot)
            }
        }
    }
    test("checkbox routes enabled command and disabled triggers do not open or write") {
        val deps = TitleRenderPorts()
        deps.settings.value = deps.settings.value.copy(enabled = false)
        withTitleRenderer(deps) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(30)) { SessionTitleSettingsComponent(vm) }
                }
                assertTrue("Available when automatic session titles are enabled." in snapshot, snapshot)
                clickTitleText(snapshot, "injected-render-default")
                clickTitleText(titleSnapshot("Title reasoning"), "[low]")
                val disabled = titleSnapshot("Title model")
                assertFalse("[first" in disabled, disabled)
                assertFalse("[max" in disabled, disabled)
                assertTrue(deps.modelWrites.isEmpty())
                assertTrue(deps.reasoningWrites.isEmpty())
                clickTitleText(disabled, "Automatic session title")
                assertEquals(listOf(true), deps.enabledWrites)
            }
        }
    }
    test("full model menu keeps unavailable current option and keyboard selects catalog model") {
        val deps = TitleRenderPorts()
        deps.settings.value = deps.settings.value.copy(model = OpenAiModelId("unavailable"))
        withTitleRenderer(deps) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(30)) { SessionTitleSettingsComponent(vm) }
                }
                clickTitleText(snapshot, "unavailable")
                val menu = titleSnapshot("[first")
                assertTrue("unavailable" in menu, menu)
                assertTrue("[second" in menu, menu)
                // Focus starts at selected unavailable (last); Up selects second.
                sendKeyEvent(KeyboardEvent(codepoint = 57352))
                titleSnapshot("[second")
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                titleSnapshot("Title generation")
                assertEquals<List<OpenAiModelId?>>(listOf(OpenAiModelId("second")), deps.modelWrites)
            }
        }
    }
    test("full reasoning menu renders fixed options keyboard choice and Escape without acceptance") {
        val deps = TitleRenderPorts()
        withTitleRenderer(deps) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(30)) { SessionTitleSettingsComponent(vm) }
                }
                clickTitleText(snapshot, "[low]")
                val menu = titleSnapshot("[max")
                for (option in listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")) {
                    assertTrue("[$option" in menu, menu)
                }
                sendKeyEvent(KeyboardEvent(codepoint = 57353))
                titleSnapshot("[medium")
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                titleSnapshot("Title generation")
                assertEquals<List<ReasoningEffort>>(listOf(ReasoningEffort.Medium), deps.reasoningWrites)
                clickTitleText(titleSnapshot("Title reasoning"), "[low]")
                titleSnapshot("[max")
                sendKeyEvent(KeyboardEvent(codepoint = 27))
                val closed = titleSnapshot("Title generation")
                assertFalse("[max" in closed, closed)
                assertEquals(1, deps.reasoningWrites.size)
            }
        }
    }
    test("application failure acknowledgement suppression and closed rendering have no second authority") {
        val deps = TitleRenderPorts()
        deps.operationFailure.value = true
        withTitleRenderer(deps) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(30)) { SessionTitleSettingsComponent(vm) }
                }
                assertTrue("A settings operation failed." in snapshot, snapshot)
                clickTitleText(snapshot, "[Dismiss]")
                assertFalse("A settings operation failed." in titleSnapshot("Title generation"))
                vm.close()
                assertFalse("Title generation" in titleSnapshot(""))
            }
        }
        deps.operationFailure.value = true
        withTitleRenderer(deps) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(30)) {
                        SessionTitleSettingsComponent(vm, showOperationFailure = false)
                    }
                }
                assertFalse("A settings operation failed." in snapshot, snapshot)
                assertTrue(vm.state.value.operationFailure)
            }
        }
    }
}

private suspend fun withTitleRenderer(deps: TitleRenderPorts, action: suspend (SessionTitleSettingsViewModel) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val vm = createSessionTitleSettingsViewModel(deps, scope)
    try { action(vm) } finally { vm.close(); scope.cancel() }
}
private suspend fun TestMosaic<String>.titleSnapshot(expected: String): String {
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
private suspend fun TestMosaic<String>.clickTitleText(snapshot: String, text: String) {
    val lines = snapshot.lines()
    val row = lines.indexOfFirst { text in it }
    assertTrue(row >= 0, snapshot)
    val column = lines[row].indexOf(text) + 1
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Press, MouseEvent.Button.Left))
    titleSnapshot("")
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Release))
    titleSnapshot("")
}
private class TitleRenderPorts : SessionTitleSettingsDependencies {
    override val settings = MutableStateFlow(SessionTitleSettings())
    override val defaultModel = OpenAiModelId("injected-render-default")
    override val models = MutableStateFlow(listOf(
        ModelInfo(OpenAiModelId("first"), "first"), ModelInfo(OpenAiModelId("second"), "second"),
    ))
    override val operationFailure = MutableStateFlow(false)
    val enabledWrites = mutableListOf<Boolean>()
    val modelWrites = mutableListOf<OpenAiModelId?>()
    val reasoningWrites = mutableListOf<ReasoningEffort>()
    override fun setEnabled(expected: Boolean, enabled: Boolean): SessionTitleWriteAdmission {
        enabledWrites += enabled
        return SessionTitleWriteAdmission.Accepted
    }
    override fun setModel(expected: OpenAiModelId?, model: OpenAiModelId?): SessionTitleWriteAdmission {
        modelWrites += model
        return SessionTitleWriteAdmission.Accepted
    }
    override fun setReasoningEffort(expected: ReasoningEffort, reasoningEffort: ReasoningEffort): SessionTitleWriteAdmission {
        reasoningWrites += reasoningEffort
        return SessionTitleWriteAdmission.Accepted
    }
    override fun reportFailure(failure: Throwable) { operationFailure.value = true }
    override fun dismissFailure() { operationFailure.value = false }
}
