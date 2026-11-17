package io.github.stream29.kodex.cli.newsessiondefaults

import androidx.compose.runtime.*
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
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.createNewSessionDefaultsViewModel
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.SettingsDropdownGroup
import io.github.stream29.kodex.cli.settings.SettingsOperationFailureBanner
import io.github.stream29.kodex.cli.settings.rememberFourChoiceDropdowns
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

val newSessionDefaultsComponentTest by testSuite {
    test("full defaults panel uses canonical four values with independent title sibling") {
        val ports = DefaultsRenderPorts()
        withDefaults(ports) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    WithMosaicComponentRenderers {
                        TuiPopupHost(Modifier.width(100).height(32)) {
                            val menus = rememberDefaultsDropdowns()
                            Column {
                                NewSessionDefaultsContent(vm, menus.renderer)
                                Text("Independent title child")
                            }
                            with(menus.renderer) { Menus() }
                        }
                    }
                }
                var previous = -1
                for (text in listOf(
                    "Model behavior", "Model [orphan]", "Reasoning [medium]",
                    "Service tier [default]", "Questions [ask user]", "Independent title child",
                )) {
                    val next = snapshot.indexOf(text)
                    assertTrue(next > previous, snapshot)
                    previous = next
                }
                assertFalse("Working directory" in snapshot, snapshot)
                assertFalse("Ultrafast:" in snapshot, snapshot)
                assertFalse("higher usage" in snapshot, snapshot)
                assertFalse("account access" in snapshot, snapshot)
                assertTrue(ports.admissions.isEmpty())
            }
        }
    }
    test("complete model reasoning tier and questions menus write one typed field each") {
        val ports = DefaultsRenderPorts()
        withDefaults(ports) { vm ->
            runMosaicTest {
                var snapshot = setContentAndSnapshot {
                    WithMosaicComponentRenderers {
                        TuiPopupHost(Modifier.width(100).height(32)) { RenderDefaults(vm) }
                    }
                }
                clickDefaults(snapshot, "Model [orphan]", "Model [".length)
                snapshot = defaultsSnapshot("[catalog-a")
                assertTrue("[catalog-b" in snapshot, snapshot)
                assertTrue("[orphan" in snapshot, snapshot)
                clickDefaults(snapshot, "[catalog-b")
                snapshot = defaultsSnapshot("Model [catalog-b]")
                clickDefaults(snapshot, "Reasoning [medium]", "Reasoning [".length)
                snapshot = defaultsSnapshot("[max")
                for (effort in listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")) {
                    assertTrue("[$effort" in snapshot, snapshot)
                }
                clickDefaults(snapshot, "[max")
                snapshot = defaultsSnapshot("Reasoning [max]")
                clickDefaults(snapshot, "Service tier [default]", "Service tier [".length)
                snapshot = defaultsSnapshot("[flex")
                for (tier in listOf("default", "fast", "flex", "ultrafast")) assertTrue("[$tier" in snapshot, snapshot)
                clickDefaults(snapshot, "[ultrafast")
                snapshot = defaultsSnapshot("Service tier [ultrafast]")
                assertFalse("higher usage" in snapshot, snapshot)
                assertFalse("account access" in snapshot, snapshot)
                clickDefaults(snapshot, "Questions [ask user]", "Questions [".length)
                snapshot = defaultsSnapshot("[no question")
                assertTrue("[ask user" in snapshot, snapshot)
                clickDefaults(snapshot, "[no question")
                defaultsSnapshot("Questions [no question]")
                assertEquals(listOf("model", "effort", "tier", "questions"), ports.admissions)
                assertEquals(
                    KodexNewSessionSettings(
                        OpenAiModelId("catalog-b"), ReasoningEffort.Max,
                        ServiceTier.Ultrafast, RequestUserInputMode.NoQuestion,
                    ),
                    ports.defaults.value,
                )
                assertEquals(4L, vm.state.value.revision)
            }
        }
    }
    test("keyboard uses selected item focus and Escape dismisses menu without admission") {
        val ports = DefaultsRenderPorts()
        withDefaults(ports) { vm ->
            runMosaicTest {
                var snapshot = setContentAndSnapshot {
                    WithMosaicComponentRenderers {
                        TuiPopupHost(Modifier.width(100).height(32)) { RenderDefaults(vm) }
                    }
                }
                clickDefaults(snapshot, "Service tier [default]", "Service tier [".length)
                defaultsSnapshot("[flex")
                sendKeyEvent(KeyboardEvent(codepoint = 57353))
                defaultsSnapshot("[flex")
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                snapshot = defaultsSnapshot("Service tier [fast]")
                assertEquals(listOf("tier"), ports.admissions)
                clickDefaults(snapshot, "Model [orphan]", "Model [".length)
                defaultsSnapshot("[catalog-a")
                sendKeyEvent(KeyboardEvent(codepoint = 27))
                snapshot = defaultsSnapshot("Model behavior")
                assertFalse("[catalog-a" in snapshot, snapshot)
                assertEquals(listOf("tier"), ports.admissions)
            }
        }
    }
    test("custom stored effort is displayed but known menu remains fixed, catalog updates do not edit defaults") {
        val ports = DefaultsRenderPorts()
        ports.defaults.value = ports.defaults.value.copy(reasoningEffort = ReasoningEffort.Custom("provider-effort"))
        withDefaults(ports) { vm ->
            runMosaicTest {
                var snapshot = setContentAndSnapshot {
                    WithMosaicComponentRenderers {
                        TuiPopupHost(Modifier.width(100).height(32)) { RenderDefaults(vm) }
                    }
                }
                assertTrue("Reasoning [provider-effort]" in snapshot, snapshot)
                ports.models.value = emptyList()
                assertEquals(0L, vm.state.value.revision)
                clickDefaults(snapshot, "Reasoning [provider-effort]", "Reasoning [".length)
                snapshot = defaultsSnapshot("[max")
                assertTrue("[none" in snapshot, snapshot)
                assertTrue("[xhigh" in snapshot, snapshot)
                sendKeyEvent(KeyboardEvent(codepoint = 27))
                defaultsSnapshot("Model behavior")
                assertTrue(ports.admissions.isEmpty())
            }
        }
    }
    test("shared failure banner acknowledges once in enclosing host while content stays banner-free") {
        val ports = DefaultsRenderPorts()
        ports.operationFailure.value = true
        withDefaults(ports) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    WithMosaicComponentRenderers {
                        TuiPopupHost(Modifier.width(100).height(32)) {
                            val failed by vm.operationFailure.collectAsState()
                            val menus = rememberDefaultsDropdowns()
                            Column {
                                SettingsOperationFailureBanner(failed, vm::dismissOperationFailure)
                                NewSessionDefaultsContent(vm, menus.renderer)
                            }
                            with(menus.renderer) { Menus() }
                        }
                    }
                }
                assertTrue("Could not confirm a Settings operation" in snapshot, snapshot)
                clickDefaults(snapshot, "[Dismiss]")
                val next = defaultsSnapshot("Model behavior")
                assertFalse("Could not confirm a Settings operation" in next, next)
                assertEquals(1, ports.dismissals)
            }
        }
        ports.operationFailure.value = true
        withDefaults(ports) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    WithMosaicComponentRenderers {
                        TuiPopupHost(Modifier.width(100).height(32)) { RenderDefaults(vm) }
                    }
                }
                assertFalse("Could not confirm a Settings operation" in snapshot, snapshot)
                assertTrue(vm.operationFailure.value)
            }
        }
    }
    test("hiding dismisses renderer menus only and preserves observation; close disables all fields") {
        val ports = DefaultsRenderPorts()
        withDefaults(ports) { vm ->
            var visible by mutableStateOf(true)
            lateinit var menus: SettingsDropdownGroup
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    WithMosaicComponentRenderers {
                        TuiPopupHost(Modifier.width(100).height(32)) {
                            menus = rememberDefaultsDropdowns()
                            if (visible) {
                                Column { NewSessionDefaultsContent(vm, menus.renderer) }
                                with(menus.renderer) { Menus() }
                            }
                        }
                    }
                }
                clickDefaults(snapshot, "Model [orphan]", "Model [".length)
                defaultsSnapshot("[catalog-a")
                assertTrue(menus.dropdowns[0].expanded)
                visible = false
                defaultsSnapshot("")
                assertFalse(menus.dropdowns[0].expanded)
                assertTrue(vm.state.value.active)
                ports.defaults.value = ports.defaults.value.copy(serviceTier = ServiceTier.Fast)
                assertEquals(1L, vm.state.value.revision)
                visible = true
                defaultsSnapshot("Service tier [fast]")
                vm.close()
                var next = defaultsSnapshot("Model behavior")
                clickDefaults(next, "Model [orphan]", "Model [".length)
                next = defaultsSnapshot("Model behavior")
                assertFalse("[catalog-a" in next, next)
                assertFalse(vm.state.value.active)
                assertTrue(ports.admissions.isEmpty())
                assertEquals(ServiceTier.Fast, ports.defaults.value.serviceTier)
            }
        }
    }
}

@Composable
private fun rememberDefaultsDropdowns(): SettingsDropdownGroup = rememberFourChoiceDropdowns(
    DefaultsChoiceField.Model.name,
    DefaultsChoiceField.Reasoning.name,
    DefaultsChoiceField.ServiceTier.name,
    DefaultsChoiceField.Questions.name,
)

@Composable
private fun BoxScope.RenderDefaults(vm: NewSessionSettingsViewModel) {
    val menus = rememberDefaultsDropdowns()
    Column { NewSessionDefaultsContent(vm, menus.renderer) }
    with(menus.renderer) { Menus() }
}

private suspend fun withDefaults(ports: DefaultsRenderPorts, action: suspend (NewSessionSettingsViewModel) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val vm = createNewSessionDefaultsViewModel(ports, scope)
    try { action(vm) } finally { vm.close(); scope.cancel() }
}

private suspend fun TestMosaic<String>.defaultsSnapshot(expected: String): String {
    var snapshot = ""
    repeat(5) {
        snapshot = try { awaitSnapshot() } catch (_: TimeoutCancellationException) {
            draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
        }
        if (expected in snapshot) return snapshot
    }
    assertTrue(expected in snapshot, snapshot)
    return snapshot
}

private suspend fun TestMosaic<String>.clickDefaults(snapshot: String, text: String, offset: Int = 1) {
    val lines = snapshot.lines()
    val row = lines.indexOfFirst { text in it }
    assertTrue(row >= 0, snapshot)
    val column = lines[row].indexOf(text) + offset
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Press, MouseEvent.Button.Left))
    defaultsSnapshot("")
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Release))
    defaultsSnapshot("")
}

private class DefaultsRenderPorts : NewSessionDefaultsDependencies {
    override val defaults = MutableStateFlow(KodexNewSessionSettings(model = OpenAiModelId("orphan")))
    override val models = MutableStateFlow(
        listOf(ModelInfo(OpenAiModelId("catalog-a"), "a"), ModelInfo(OpenAiModelId("catalog-b"), "b")),
    )
    override val operationFailure = MutableStateFlow(false)
    val admissions = mutableListOf<String>()
    var dismissals = 0
    override fun admitModel(expected: OpenAiModelId, requested: OpenAiModelId): Boolean {
        assertEquals(defaults.value.model, expected)
        admissions += "model"; defaults.value = defaults.value.copy(model = requested)
        return true
    }
    override fun admitReasoningEffort(expected: ReasoningEffort, requested: ReasoningEffort): Boolean {
        assertEquals(defaults.value.reasoningEffort, expected)
        admissions += "effort"; defaults.value = defaults.value.copy(reasoningEffort = requested)
        return true
    }
    override fun admitServiceTier(expected: ServiceTier, requested: ServiceTier): Boolean {
        assertEquals(defaults.value.serviceTier, expected)
        admissions += "tier"; defaults.value = defaults.value.copy(serviceTier = requested)
        return true
    }
    override fun admitRequestUserInputMode(
        expected: RequestUserInputMode, requested: RequestUserInputMode,
    ): Boolean {
        assertEquals(defaults.value.requestUserInputMode, expected)
        admissions += "questions"; defaults.value = defaults.value.copy(requestUserInputMode = requested)
        return true
    }
    override fun reportFailure(failure: Throwable) { operationFailure.value = true }
    override fun dismissFailure() { dismissals++; operationFailure.value = false }
}
