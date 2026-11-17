package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.*
import io.github.stream29.kodex.app.contextsourcesettings.*
import io.github.stream29.kodex.app.settings.contract.BuiltInContextSource
import io.github.stream29.kodex.app.settings.contract.SettingsWriteAdmission
import io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers
import io.github.stream29.kodex.cli.components.TuiPopupHost
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val contextSourceSettingsComponentTest by testSuite {
    test("full renderer orders all built-ins custom empty branch and shared acknowledgement") {
        val deps = ContextRenderPorts()
        deps.operationFailure.value = true
        withContextRenderer(deps) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    WithMosaicComponentRenderers {
                        TuiPopupHost(Modifier.width(100).height(35)) {
                            val state by vm.state.collectAsState()
                            val renderer = remember { MosaicSettingsRenderer() }
                            Column {
                                SettingsOperationFailureBanner(state.operationFailure, vm::dismissFailure)
                                ContextSourceSettingsContent(vm, renderer)
                            }
                            ContextSourceSettingsDialogHost(vm, renderer) { title, onDismiss, content ->
                                SettingsModalDialogFrame(title, 96, onDismiss, content)
                            }
                        }
                    }
                }
                var position = -1
                for (label in listOf(
                    "Built-in sources", "Agents home", "Kodex home", "Codex home",
                    "Git root", "Working directory", "Custom sources", "Add source", "None configured",
                )) {
                    val next = snapshot.indexOf(label)
                    assertTrue(next > position, snapshot)
                    position = next
                }
                for (path in listOf("~/.agents/", "~/.kodex/", "~/.codex/", "<git-root>/", "<cwd>/")) {
                    assertTrue(path in snapshot, snapshot)
                }
                assertTrue("Could not confirm a Settings operation" in snapshot, snapshot)
                clickContextText(snapshot, "[Dismiss]")
                val acknowledged = contextSnapshot("Built-in sources")
                assertFalse("Could not confirm a Settings operation" in acknowledged, acknowledged)
            }
        }
    }
    test("complete list controls route exact built-in and custom path commands") {
        val deps = ContextRenderPorts()
        deps.sources.value = deps.sources.value.copy(customSources = listOf(AgentContextCustomSource("/custom")))
        withContextRenderer(deps) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    WithMosaicComponentRenderers {
                        TuiPopupHost(Modifier.width(100).height(35)) { RenderContextSettings(vm) }
                    }
                }
                assertTrue("/custom" in snapshot, snapshot)
                assertTrue("Global context source" in snapshot, snapshot)
                clickContextText(snapshot, "Agents home")
                assertEquals(BuiltInContextSource.AgentsHome to false, deps.builtIns.single())
                clickContextText(contextSnapshot("/custom"), "[x] Enabled")
                assertEquals("/custom" to false, deps.custom.single())
                clickContextText(contextSnapshot("/custom"), "[Remove]")
                assertEquals("/custom", deps.removed.single().path)
            }
        }
    }
    test("renderer opens add and keyboard typing plus Add uses only VM draft once") {
        val deps = ContextRenderPorts()
        withContextRenderer(deps) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    WithMosaicComponentRenderers {
                        TuiPopupHost(Modifier.width(100).height(35)) { RenderContextSettings(vm) }
                    }
                }
                clickContextText(snapshot, "[Add source]")
                contextSnapshot("Add context source")
                for (char in "/new") sendKeyEvent(KeyboardEvent(codepoint = char.code))
                contextSnapshot("/new")
                val token = (vm.state.value.dialog as ContextSourceSettingsDialog.Adding).token
                assertEquals("/new", (vm.state.value.dialog as ContextSourceSettingsDialog.Adding).draft)
                repeat(2) { sendKeyEvent(KeyboardEvent(codepoint = 9)) }
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                contextSnapshot("Built-in sources")
                assertEquals("/new", deps.saved.single().last().path)
                vm.save(token)
                assertEquals(1, deps.saved.size)
                assertEquals(ContextSourceSettingsDialog.Hidden, vm.state.value.dialog)
            }
        }
    }
    test("VM validation rejection and programmatic draft changes are rendered without local error authority") {
        val deps = ContextRenderPorts()
        withContextRenderer(deps) { vm ->
            vm.add()
            val token = (vm.state.value.dialog as ContextSourceSettingsDialog.Adding).token
            vm.save(token)
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    WithMosaicComponentRenderers {
                        TuiPopupHost(Modifier.width(100).height(35)) { RenderContextSettings(vm) }
                    }
                }
                assertTrue("Enter an absolute path, ~, or ~/path." in snapshot, snapshot)
                sendKeyEvent(KeyboardEvent(codepoint = '/'.code))
                contextSnapshot("/")
                assertEquals("/", (vm.state.value.dialog as ContextSourceSettingsDialog.Adding).draft)
                assertNull((vm.state.value.dialog as ContextSourceSettingsDialog.Adding).error)
                vm.updateDraft(token, "/from-vm")
                contextSnapshot("/from-vm")
                deps.admission = SettingsWriteAdmission.Rejected("Queue unavailable.")
                vm.save(token)
                val rejected = contextSnapshot("Queue unavailable.")
                assertTrue("/from-vm" in rejected, rejected)
                clickContextText(rejected, "[Cancel]")
                contextSnapshot("Built-in sources")
                assertEquals(ContextSourceSettingsDialog.Hidden, vm.state.value.dialog)
                assertTrue(deps.saved.isEmpty())
            }
        }
    }
    test("replacement late callback Escape hide and closed branches discard only unaccepted draft") {
        val deps = ContextRenderPorts()
        deps.operationFailure.value = true
        withContextRenderer(deps) { vm ->
            vm.add()
            val old = (vm.state.value.dialog as ContextSourceSettingsDialog.Adding).token
            vm.add()
            val next = (vm.state.value.dialog as ContextSourceSettingsDialog.Adding).token
            vm.updateDraft(next, "/replacement")
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    WithMosaicComponentRenderers {
                        TuiPopupHost(Modifier.width(100).height(35)) { RenderContextSettings(vm) }
                    }
                }
                assertTrue("/replacement" in snapshot, snapshot)
                assertFalse("Could not confirm a Settings operation" in snapshot, snapshot)
                vm.save(old)
                vm.dismiss(old)
                assertSame(next, (vm.state.value.dialog as ContextSourceSettingsDialog.Adding).token)
                sendKeyEvent(KeyboardEvent(codepoint = 27))
                contextSnapshot("Built-in sources")
                assertEquals(ContextSourceSettingsDialog.Hidden, vm.state.value.dialog)
                vm.add()
                contextSnapshot("Add context source")
                vm.hidePage()
                contextSnapshot("Built-in sources")
                vm.close()
                val closed = contextSnapshot("")
                assertFalse("Built-in sources" in closed, closed)
                assertTrue(deps.saved.isEmpty())
            }
        }
    }
}

@Composable
private fun BoxScope.RenderContextSettings(vm: ContextSourceSettingsViewModel) {
    val renderer = remember { MosaicSettingsRenderer() }
    Column { ContextSourceSettingsContent(vm, renderer) }
    ContextSourceSettingsDialogHost(vm, renderer) { title, onDismiss, content ->
        SettingsModalDialogFrame(title, 96, onDismiss, content)
    }
}

private suspend fun withContextRenderer(
    deps: ContextRenderPorts, action: suspend (ContextSourceSettingsViewModel) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val vm = createContextSourceSettingsViewModel(deps, scope)
    try { action(vm) } finally { vm.close(); scope.cancel() }
}

private suspend fun TestMosaic<String>.contextSnapshot(expected: String): String {
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

private suspend fun TestMosaic<String>.clickContextText(snapshot: String, text: String) {
    val lines = snapshot.lines()
    val row = lines.indexOfFirst { text in it }
    assertTrue(row >= 0, snapshot)
    val column = lines[row].indexOf(text) + 1
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Press, MouseEvent.Button.Left))
    contextSnapshot("")
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Release))
    contextSnapshot("")
}

private class ContextRenderPorts : ContextSourceSettingsDependencies {
    override val sources = MutableStateFlow(AgentContextSourceSettings())
    override val operationFailure = MutableStateFlow(false)
    override val pathPolicy = object : ContextSourcePathPolicy {
        override suspend fun evaluate(candidate: String, originalPaths: List<String>): ContextPathEvaluation {
            fun compare(path: String): ContextPathComparison {
                val key = normalize(path)
                return ContextPathComparison(key, key == "/home/test/.agents")
            }
            return ContextPathEvaluation.Evaluated(compare(candidate), originalPaths.map(::compare))
        }
        private fun normalize(path: String): String? = when {
            path.isBlank() || '$' in path -> null
            path == "~" -> "/home/test"
            path.startsWith("~/") -> "/home/test/" + path.substring(2)
            path.startsWith("/") -> path
            else -> null
        }
    }
    var admission: SettingsWriteAdmission = SettingsWriteAdmission.Accepted
    val saved = mutableListOf<List<AgentContextCustomSource>>()
    val builtIns = mutableListOf<Pair<BuiltInContextSource, Boolean>>()
    val custom = mutableListOf<Pair<String, Boolean>>()
    val removed = mutableListOf<AgentContextCustomSource>()
    override fun setBuiltInEnabled(source: BuiltInContextSource, expected: Boolean, enabled: Boolean): SettingsWriteAdmission {
        builtIns += source to enabled
        return admission
    }
    override fun replaceCustomSources(
        expected: List<AgentContextCustomSource>, updated: List<AgentContextCustomSource>,
    ): SettingsWriteAdmission {
        if (admission == SettingsWriteAdmission.Accepted) saved += updated.toList()
        return admission
    }
    override fun setCustomEnabled(original: AgentContextCustomSource, enabled: Boolean): SettingsWriteAdmission {
        custom += original.path to enabled
        return admission
    }
    override fun removeCustom(original: AgentContextCustomSource): SettingsWriteAdmission {
        removed += original
        return admission
    }
    override fun reportFailure(failure: Throwable) { operationFailure.value = true }
    override fun dismissFailure() { operationFailure.value = false }
}
