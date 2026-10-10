@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.cli.runtimeconfiguration

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.clipToBounds
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.Constraints
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfiguration
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationModelOption
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationState
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val runtimeConfigurationRendererTest by testSuite {
    test("labels preserve custom effort and omit only default tier") {
        val model = OpenAiModelId("gpt-5.6-sol")
        assertEquals("gpt-5.6-sol max", runtimeConfigurationLabel(model, ReasoningEffort.Max, ServiceTier.Default))
        assertEquals("gpt-5.6-sol max fast", runtimeConfigurationLabel(model, ReasoningEffort.Max, ServiceTier.Fast))
        assertEquals("gpt-5.6-sol custom flex",
            runtimeConfigurationLabel(model, ReasoningEffort.Custom("custom"), ServiceTier.Flex))
        assertEquals("gpt-5.6-sol max ultrafast",
            runtimeConfigurationLabel(model, ReasoningEffort.Max, ServiceTier.Ultrafast))
        assertEquals("ask user", runtimeRequestUserInputModeLabel(RequestUserInputMode.AskUser))
        assertEquals("no question", runtimeRequestUserInputModeLabel(RequestUserInputMode.NoQuestion))
    }
    test("real three-level menu submits atomic tuple once and model effort navigation writes nothing") {
        val vm = RuntimeFakeViewModel()
        runMosaicTest {
            val initial = setContentAndSnapshot {
                val dropdowns = RuntimeConfigurationDropdowns.remember(vm)
                TuiPopupHost(modifier = Modifier.width(60).height(12)) {
                    RuntimeConfigurationTriggers(vm, dropdowns)
                    RuntimeConfigurationMenus(vm, dropdowns)
                }
            }
            assertTrue("[test-model max] [ask user]" in initial, initial)
            click(initial.indexOf("[test-model max]") + 1)
            awaitSnapshotContaining("[test-model >]")
            assertTrue(vm.tuples.isEmpty())
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
            awaitSnapshotContaining("[max >]")
            assertTrue(vm.tuples.isEmpty())
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
            val tiers = awaitSnapshotContaining("[default]")
            assertTrue("fast" in tiers, tiers)
            assertTrue(vm.tuples.isEmpty())
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshotContaining("[test-model max fast]")
            assertEquals(listOf(MenuTuple(OpenAiModelId("test-model"), ReasoningEffort.Max, ServiceTier.Fast)), vm.tuples)
            assertTrue(vm.modes.isEmpty())
            assertEquals(0, vm.closeCount)
        }
        assertEquals(0, vm.closeCount)
    }
    test("questions menu writes only mode once") {
        val vm = RuntimeFakeViewModel()
        runMosaicTest {
            val initial = setContentAndSnapshot {
                val dropdowns = RuntimeConfigurationDropdowns.remember(vm)
                TuiPopupHost(modifier = Modifier.width(60).height(8)) {
                    RuntimeConfigurationTriggers(vm, dropdowns)
                    RuntimeConfigurationMenus(vm, dropdowns)
                }
            }
            click(initial.indexOf("[ask user]") + 1)
            val menu = awaitSnapshotContaining("no question")
            assertTrue("[ask user]" in menu, menu)
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitRuntimeCondition { vm.modes == listOf(RequestUserInputMode.NoQuestion) }
            awaitSnapshotContaining("[no question]")
        }
        assertEquals(listOf(RequestUserInputMode.NoQuestion), vm.modes)
        assertTrue(vm.tuples.isEmpty())
    }
    test("Ultrafast advertises usage and access before opt-in on a narrow menu") {
        val vm = RuntimeFakeViewModel()
        vm.state.value = vm.state.value.copy(modelOptions = listOf(
            RuntimeConfigurationModelOption(
                OpenAiModelId("test-model"), listOf(ReasoningEffort.Max), ServiceTier.entries.toList(),
            ),
        ))
        runMosaicTest {
            val initial = setContentAndSnapshot {
                val dropdowns = RuntimeConfigurationDropdowns.remember(vm)
                TuiPopupHost(modifier = Modifier.width(32).height(14)) {
                    RuntimeConfigurationTriggers(vm, dropdowns)
                    RuntimeConfigurationMenus(vm, dropdowns)
                }
            }
            click(initial.indexOf("[test-model max]") + 1)
            awaitSnapshotContaining("[test-model >]")
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
            awaitSnapshotContaining("[max >]")
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
            val menu = awaitSnapshotContaining("higher usage")
            assertTrue("access/model" in menu, menu)
            assertTrue("support" in menu, menu)
            assertTrue("required" in menu, menu)
            assertTrue(vm.tuples.isEmpty())
            repeat(3) { sendKeyEvent(KeyboardEvent(KeyboardEvent.Down)) }
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshotContaining("[test-model max ultrafast]")
            assertEquals(listOf(MenuTuple(OpenAiModelId("test-model"), ReasoningEffort.Max, ServiceTier.Ultrafast)), vm.tuples)
        }
    }
    test("selected model effort and tier focus bypass first descendant options and Escape never writes") {
        val vm = RuntimeFakeViewModel()
        vm.state.value = vm.state.value.copy(
            configuration = vm.state.value.configuration.copy(tier = ServiceTier.Fast),
            modelOptions = listOf(
                RuntimeConfigurationModelOption(
                    OpenAiModelId("first-model"), listOf(ReasoningEffort.Low), listOf(ServiceTier.Default),
                ),
                RuntimeConfigurationModelOption(
                    OpenAiModelId("test-model"), listOf(ReasoningEffort.Low, ReasoningEffort.Max),
                    listOf(ServiceTier.Default, ServiceTier.Fast),
                ),
            ),
        )
        runMosaicTest {
            val initial = setContentAndSnapshot {
                val dropdowns = RuntimeConfigurationDropdowns.remember(vm)
                TuiPopupHost(modifier = Modifier.width(75).height(12)) {
                    RuntimeConfigurationTriggers(vm, dropdowns)
                    RuntimeConfigurationMenus(vm, dropdowns)
                }
            }
            click(initial.indexOf("[test-model max fast]") + 1)
            awaitSnapshotContaining("[test-model >]")
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
            awaitSnapshotContaining("[max >]")
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
            awaitSnapshotContaining("[fast]")
            // Escape/left close descendants. No tuple has been selected.
            sendKeyEvent(KeyboardEvent(codepoint = 27))
            awaitSnapshot()
            sendKeyEvent(KeyboardEvent(codepoint = 27))
            awaitSnapshot()
            sendKeyEvent(KeyboardEvent(codepoint = 27))
            awaitSnapshot()
        }
        assertTrue(vm.tuples.isEmpty())
        assertTrue(vm.modes.isEmpty())
    }
    test("missing catalog custom effort fallback renders default tier without correcting saved tier") {
        val vm = RuntimeFakeViewModel()
        val custom = ReasoningEffort.Custom("unknown")
        vm.state.value = RuntimeConfigurationState(
            configuration = RuntimeConfiguration(OpenAiModelId("missing"), custom, ServiceTier.Flex, RequestUserInputMode.AskUser),
            modelOptions = listOf(
                RuntimeConfigurationModelOption(OpenAiModelId("missing"), listOf(custom), listOf(ServiceTier.Default)),
            ),
        )
        runMosaicTest {
            val initial = setContentAndSnapshot {
                val dropdowns = RuntimeConfigurationDropdowns.remember(vm)
                TuiPopupHost(modifier = Modifier.width(65).height(12)) {
                    RuntimeConfigurationTriggers(vm, dropdowns)
                    RuntimeConfigurationMenus(vm, dropdowns)
                }
            }
            assertTrue("[missing unknown flex]" in initial, initial)
            click(initial.indexOf("[missing unknown flex]") + 1)
            awaitSnapshotContaining("[missing >]")
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
            awaitSnapshotContaining("[unknown >]")
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
            awaitSnapshotContaining("[default]")
            assertEquals(ServiceTier.Flex, vm.state.value.configuration.tier)
            assertTrue(vm.tuples.isEmpty())
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshotContaining("[missing unknown]")
        }
        assertEquals(listOf(MenuTuple(OpenAiModelId("missing"), custom, ServiceTier.Default)), vm.tuples)
    }
    test("no spacing emits two direct host measurables alongside other status controls") {
        val vm = RuntimeFakeViewModel()
        var measuredChildren = 0
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                val dropdowns = RuntimeConfigurationDropdowns.remember(vm)
                Layout(content = {
                    Text("12t")
                    TuiButton(label = "Stop", onClick = {})
                    RuntimeConfigurationStatusItemsWithoutSpacing(vm, dropdowns)
                    TuiButton(label = "cwd", onClick = {})
                    TuiButton(label = "Settings", onClick = {})
                }, modifier = Modifier.width(80).clipToBounds()) { measurables, _ ->
                    measuredChildren = measurables.size
                    val children = measurables.map {
                        it.measure(Constraints(maxWidth = Constraints.Infinity, maxHeight = 1))
                    }
                    layout(80, 1) {
                        var x = 0
                        children.forEach { child ->
                            child.place(x, 0)
                            x += child.width + 1
                        }
                    }
                }
            }
            assertEquals(6, measuredChildren)
            assertTrue("12t [Stop] [test-model max] [ask user] [cwd] [Settings]" in snapshot, snapshot)
        }
        // State-only no-spacing overload does not create a spacer or wrapper Row either.
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Row {
                    RuntimeConfigurationStatusItemsWithoutSpacing(
                        vm.state.value, RuntimeConfigurationDropdowns.remember(vm),
                    )
                }
            }
            assertEquals("[test-model max][ask user]", snapshot)
        }
    }
    test("owner switch discards open menus and closed state removes both triggers and menus") {
        val old = RuntimeFakeViewModel()
        val next = RuntimeFakeViewModel()
        next.state.value = next.state.value.copy(
            configuration = next.state.value.configuration.copy(model = OpenAiModelId("next-model")),
            modelOptions = listOf(RuntimeConfigurationModelOption(
                OpenAiModelId("next-model"), listOf(ReasoningEffort.Max), listOf(ServiceTier.Default),
            )),
        )
        var current by mutableStateOf<RuntimeConfigurationViewModel>(old)
        lateinit var handles: RuntimeConfigurationDropdowns
        runMosaicTest {
            val initial = setContentAndSnapshot {
                handles = RuntimeConfigurationDropdowns.remember(current)
                TuiPopupHost(modifier = Modifier.width(60).height(12)) {
                    RuntimeConfigurationTriggers(current, handles)
                    RuntimeConfigurationMenus(current, handles)
                }
            }
            val oldHandles = handles
            click(initial.indexOf("[test-model max]") + 1)
            awaitSnapshotContaining("[test-model >]")
            assertTrue(oldHandles.model.expanded)
            current = next
            val switched = awaitSnapshotContaining("[next-model max]")
            assertFalse("test-model >" in switched, switched)
            assertFalse(oldHandles.model.expanded)
            assertFalse(handles.model.expanded)
            // Let removal of the old popup finish restoring focus/hit targets.
            try { awaitSnapshot(50.milliseconds) } catch (_: TimeoutCancellationException) { }
            click(switched.indexOf("[next-model max]") + 1)
            assertTrue(handles.model.expanded)
            awaitSnapshotContaining("[next-model >]")
            next.close()
            awaitRuntimeCondition { !handles.model.expanded }
            val closed = draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
            assertFalse("next-model" in closed, closed)
            assertFalse("ask user" in closed, closed)
            assertFalse(handles.model.expanded)
        }
        assertEquals(0, old.closeCount) // Renderer borrows, never owns either child.
        assertEquals(1, next.closeCount)
        assertTrue(old.tuples.isEmpty())
        assertTrue(next.tuples.isEmpty())
    }
    test("narrow trigger rows retain both controls and disabling dismisses menus without writing") {
        val vm = RuntimeFakeViewModel()
        listOf(30, 40, 60).forEach { columns ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(modifier = Modifier.width(columns).height(8)) {
                        RuntimeConfigurationTriggers(vm.state.value, RuntimeConfigurationDropdowns.remember(vm))
                    }
                }
                assertTrue("[test-model max] [ask user]" in snapshot, snapshot)
                assertTrue(snapshot.lines().all { it.length <= columns }, snapshot)
            }
        }
        var enabled by mutableStateOf(true)
        lateinit var dropdowns: RuntimeConfigurationDropdowns
        runMosaicTest {
            val initial = setContentAndSnapshot {
                dropdowns = RuntimeConfigurationDropdowns.remember(vm)
                TuiPopupHost(modifier = Modifier.width(60).height(12)) {
                    RuntimeConfigurationTriggers(vm, dropdowns, enabled)
                    RuntimeConfigurationMenus(vm, dropdowns)
                }
            }
            click(initial.indexOf("[test-model max]") + 1)
            awaitSnapshotContaining("[test-model >]")
            enabled = false
            awaitRuntimeCondition { !dropdowns.model.expanded }
            assertFalse(dropdowns.model.expanded)
            assertFalse(dropdowns.requestUserInputMode.expanded)
        }
        assertTrue(vm.tuples.isEmpty())
        assertTrue(vm.modes.isEmpty())
    }
    test("catalog capability updates refresh real open menus without a write") {
        val vm = RuntimeFakeViewModel()
        runMosaicTest {
            val initial = setContentAndSnapshot {
                val dropdowns = RuntimeConfigurationDropdowns.remember(vm)
                TuiPopupHost(modifier = Modifier.width(70).height(12)) {
                    RuntimeConfigurationTriggers(vm, dropdowns)
                    RuntimeConfigurationMenus(vm, dropdowns)
                }
            }
            click(initial.indexOf("[test-model max]") + 1)
            awaitSnapshotContaining("[test-model >]")
            vm.state.value = vm.state.value.copy(modelOptions = listOf(
                RuntimeConfigurationModelOption(
                    OpenAiModelId("test-model"), listOf(ReasoningEffort.Custom("fresh")),
                    listOf(ServiceTier.Default),
                ),
            ))
            awaitSnapshot()
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
            awaitSnapshotContaining("[fresh >]")
        }
        assertTrue(vm.tuples.isEmpty())
        assertEquals(ReasoningEffort.Max, vm.state.value.configuration.reasoning)
    }
    for (replaceOwner in listOf(false, true)) {
    test("renderer disposal replaceOwner=$replaceOwner cancels command wait without closing borrowed child") {
        val vm = RuntimeFakeViewModel()
        val replacement = RuntimeFakeViewModel().apply {
            state.value = state.value.copy(
                configuration = state.value.configuration.copy(model = OpenAiModelId("replacement")),
            )
        }
        vm.gate = CompletableDeferred()
        var shown by mutableStateOf(true)
        var current by mutableStateOf<RuntimeConfigurationViewModel>(vm)
        runMosaicTest {
            val initial = setContentAndSnapshot {
                if (shown) {
                    val dropdowns = RuntimeConfigurationDropdowns.remember(current)
                    TuiPopupHost(modifier = Modifier.width(60).height(12)) {
                        RuntimeConfigurationTriggers(current, dropdowns)
                        RuntimeConfigurationMenus(current, dropdowns)
                    }
                } else {
                    Text("unmounted")
                }
            }
            click(initial.indexOf("[ask user]") + 1)
            awaitSnapshotContaining("no question")
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitRuntimeCondition { vm.modes == listOf(RequestUserInputMode.NoQuestion) }
            assertEquals(listOf(RequestUserInputMode.NoQuestion), vm.modes)
            if (replaceOwner) {
                current = replacement
                awaitSnapshotContaining("[replacement max]")
            } else {
                shown = false
                awaitSnapshotContaining("unmounted")
            }
            awaitRuntimeCondition { vm.cancelled == 1 }
            assertEquals(1, vm.cancelled)
            assertEquals(0, vm.completed)
            assertEquals(0, vm.closeCount)
            assertTrue(replacement.modes.isEmpty())
            assertTrue(replacement.tuples.isEmpty())
            assertEquals(0, replacement.closeCount)
        }
    }
    }
}

private data class MenuTuple(val model: OpenAiModelId, val effort: ReasoningEffort, val tier: ServiceTier)

private class RuntimeFakeViewModel : RuntimeConfigurationViewModel {
    override val state = MutableStateFlow(
        RuntimeConfigurationState(
            configuration = RuntimeConfiguration(
                OpenAiModelId("test-model"), ReasoningEffort.Max, ServiceTier.Default, RequestUserInputMode.AskUser,
            ),
            modelOptions = listOf(
                RuntimeConfigurationModelOption(
                    OpenAiModelId("test-model"), listOf(ReasoningEffort.Max), listOf(ServiceTier.Default, ServiceTier.Fast),
                ),
            ),
        ),
    )
    val tuples = mutableListOf<MenuTuple>()
    val modes = mutableListOf<RequestUserInputMode>()
    var closeCount = 0
    var cancelled = 0
    var completed = 0
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun updateModelConfiguration(model: OpenAiModelId, effort: ReasoningEffort, tier: ServiceTier) {
        tuples += MenuTuple(model, effort, tier)
        waitForReceipt()
        state.value = state.value.copy(configuration = state.value.configuration.copy(model = model, reasoning = effort, tier = tier))
    }
    override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode) {
        modes += mode
        waitForReceipt()
        state.value = state.value.copy(configuration = state.value.configuration.copy(requestUserInputMode = mode))
    }
    private suspend fun waitForReceipt() {
        try {
            gate?.await()
            completed++
        } catch (cancel: CancellationException) {
            cancelled++
            throw cancel
        }
    }
    override fun close() {
        closeCount++
        state.value = state.value.copy(closed = true)
    }
}

private suspend fun TestMosaic<String>.click(column: Int) {
    sendMouseEvent(MouseEvent(column, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
    awaitSnapshot()
    sendMouseEvent(MouseEvent(column, 0, MouseEvent.Type.Release))
    awaitSnapshot()
}

private suspend fun TestMosaic<String>.awaitSnapshotContaining(expected: String): String {
    var latest = ""
    repeat(5) {
        latest = try {
            awaitSnapshot()
        } catch (_: TimeoutCancellationException) {
            draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
        }
        latest = latest.replace(Regex(" +]"), "]")
        if (expected.replace(Regex(" +"), " ") in latest.replace(Regex(" +"), " ")) return latest
    }
    assertTrue(expected in latest, latest)
    return latest
}

private suspend fun TestMosaic<String>.awaitRuntimeCondition(condition: () -> Boolean) {
    repeat(100) {
        if (condition()) return
        try { awaitSnapshot(50.milliseconds) } catch (_: TimeoutCancellationException) { }
    }
    assertTrue(condition(), draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false))
}
