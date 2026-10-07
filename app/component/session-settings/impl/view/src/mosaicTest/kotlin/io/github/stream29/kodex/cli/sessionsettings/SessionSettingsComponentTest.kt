@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.cli.sessionsettings

import androidx.compose.runtime.*
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.pathpicker.contract.*
import io.github.stream29.kodex.app.settings.createSessionSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

val sessionSettingsComponentTest by testSuite {
    for (kind in SessionSettingsTargetKind.entries) {
        test("$kind full panel has identity then all four fields without writes on mount") {
            val ports = SessionRenderSource(kind)
            withRenderer(ports) { vm, _ ->
                runMosaicTest {
                    val snapshot = setContentAndSnapshot {
                        TuiPopupHost(Modifier.width(100).height(32)) { SessionSettingsComponent(vm) }
                    }
                    var previous = -1
                    for (text in listOf("Identity", "Session name", "Target", "Working directory", "/original",
                        "Model behavior", "Model [orphan]", "Reasoning [medium]", "Service tier [default]",
                        "Questions [ask user]")) {
                        val next = snapshot.indexOf(text)
                        assertTrue(next > previous, snapshot)
                        previous = next
                    }
                    assertTrue("[Rename]" in snapshot, snapshot)
                    assertTrue("[Browse]" in snapshot, snapshot)
                    assertTrue(ports.writes.isEmpty())
                    assertTrue(ports.renames.isEmpty())
                }
            }
        }
    }
    test("all four full menus keep fixed known choices and submit displayed settings") {
        val ports = SessionRenderSource()
        withRenderer(ports) { vm, _ ->
            runMosaicTest {
                var snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(32)) { SessionSettingsComponent(vm) }
                }
                clickText(snapshot, "Model [orphan]", "Model [".length)
                snapshot = snapshotWith("[catalog-a")
                assertTrue("[catalog-b" in snapshot, snapshot)
                assertTrue("[orphan" in snapshot, snapshot)
                clickText(snapshot, "[catalog-a")
                snapshot = snapshotWith("Model [catalog-a]")
                clickText(snapshot, "Reasoning [medium]", "Reasoning [".length)
                snapshot = snapshotWith("[max")
                for (effort in listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")) {
                    assertTrue("[$effort" in snapshot, snapshot)
                }
                // Catalog advertises no capabilities; Settings still offers all known efforts.
                clickText(snapshot, "[max")
                snapshot = snapshotWith("Reasoning [max]")
                clickText(snapshot, "Service tier [default]", "Service tier [".length)
                snapshot = snapshotWith("[flex")
                for (tier in listOf("default", "fast", "flex")) assertTrue("[$tier" in snapshot, snapshot)
                clickText(snapshot, "[flex")
                snapshot = snapshotWith("Service tier [flex]")
                clickText(snapshot, "Questions [ask user]", "Questions [".length)
                snapshot = snapshotWith("[no question")
                assertTrue("[ask user" in snapshot, snapshot)
                clickText(snapshot, "[no question")
                snapshotWith("Questions [no question]")
                assertEquals(4, ports.writes.size)
                val settings = assertIs<SessionSettingsDataState.Available>(ports.state.value).snapshot.configuration
                assertEquals(OpenAiModelId("catalog-a"), settings.model)
                assertEquals(ReasoningEffort.Max, settings.reasoningEffort)
                assertEquals(ServiceTier.Flex, settings.serviceTier)
                assertEquals(RequestUserInputMode.NoQuestion, settings.requestUserInputMode)
                assertEquals(Path("/original"), settings.workingDirectory)
            }
        }
    }
    test("keyboard selects from current menu focus and Escape dismisses without another edit") {
        val ports = SessionRenderSource()
        withRenderer(ports) { vm, _ ->
            runMosaicTest {
                var snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(32)) { SessionSettingsComponent(vm) }
                }
                clickText(snapshot, "Service tier [default]", "Service tier [".length)
                snapshotWith("[flex")
                sendKeyEvent(KeyboardEvent(codepoint = 57353))
                snapshotWith("[flex")
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                snapshot = snapshotWith("Service tier [fast]")
                assertEquals(1, ports.writes.size)
                clickText(snapshot, "Reasoning [medium]", "Reasoning [".length)
                snapshotWith("[minimal")
                sendKeyEvent(KeyboardEvent(codepoint = 27))
                snapshot = snapshotWith("Identity")
                assertFalse("[minimal" in snapshot, snapshot)
                assertEquals(1, ports.writes.size)
            }
        }
    }
    test("noneditable fields and Browse are disabled but owned rename still opens with labeled presentation") {
        val ports = SessionRenderSource()
        ports.publish(editable = false)
        withRenderer(ports) { vm, browsers ->
            runMosaicTest {
                var snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(32)) { SessionSettingsComponent(vm) }
                }
                clickText(snapshot, "Model [orphan]", "Model [".length)
                snapshot = snapshotWith("Identity")
                assertFalse("[catalog-a" in snapshot, snapshot)
                clickText(snapshot, "[Browse]")
                assertTrue(browsers.isEmpty())
                clickText(snapshotWith("Identity"), "[Rename]")
                snapshot = snapshotWith("Rename session")
                assertTrue("Session name" in snapshot, snapshot)
                val handle = assertNotNull(vm.rename.value)
                sendKeyEvent(KeyboardEvent(codepoint = 'X'.code))
                snapshotWith("TargetX")
                assertEquals("TargetX", handle.viewModel.draftName.value)
                sendKeyEvent(KeyboardEvent(codepoint = 13, modifiers = KeyboardEvent.ModifierShift))
                assertTrue(ports.renames.isEmpty())
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                snapshotWith("Identity")
                assertEquals(listOf(handle.expectedRevision to "TargetX"), ports.renames)
                assertNull(vm.rename.value)
                assertFalse(handle.viewModel.isActive)
            }
        }
    }
    test("borrowed exact directory renderer filters Escape and confirms without a second browser owner") {
        val ports = SessionRenderSource()
        withRenderer(ports) { vm, browsers ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(32)) { SessionSettingsComponent(vm) }
                }
                clickText(snapshot, "[Browse]")
                snapshotWith("Select directory")
                val handle = assertNotNull(vm.directoryPicker.value)
                val browser = browsers.single()
                assertSame(browser, handle.selection.picker)
                sendKeyEvent(KeyboardEvent(codepoint = 'A'.code))
                snapshotWith("Filter: A")
                assertEquals("A", browser.state.value.filterQuery)
                sendKeyEvent(KeyboardEvent(codepoint = 27))
                snapshotWith("Filter: type letters")
                assertSame(handle, vm.directoryPicker.value)
                clickText(snapshotWith("[Select]"), "[Select]")
                repeat(100) {
                    if (vm.directoryPicker.value != null) {
                        try { awaitSnapshot(50.milliseconds) } catch (_: TimeoutCancellationException) { }
                    }
                }
                snapshotWith("Identity")
                assertNull(vm.directoryPicker.value)
                assertEquals(1, browser.closes)
                assertEquals(1, ports.writes.size)
            }
        }
    }
    test("page removal disposes exact children but keeps observations and cannot dismiss replacement handles") {
        val ports = SessionRenderSource()
        withRenderer(ports) { vm, browsers ->
            var visible by mutableStateOf(true)
            runMosaicTest {
                setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(32)) {
                        if (visible) SessionSettingsComponent(vm)
                    }
                }
                vm.requestRename(0)
                snapshotWith("Rename session")
                val old = assertNotNull(vm.rename.value)
                vm.requestRename(0)
                snapshotWith("Rename session")
                val current = assertNotNull(vm.rename.value)
                assertFalse(old.viewModel.isActive)
                assertFalse(vm.dismissRename(old))
                assertSame(current, vm.rename.value)
                visible = false
                snapshotWith("")
                assertNull(vm.rename.value)
                assertFalse(current.viewModel.isActive)
                assertEquals(0, ports.closes)
                ports.publish(name = "External")
                assertEquals("External", assertIs<SessionSettingsState.Available>(vm.state.value).snapshot.sessionName)
                visible = true
                snapshotWith("External")
                vm.requestWorkingDirectory(assertIs<SessionSettingsState.Available>(vm.state.value).snapshot.revision)
                snapshotWith("Select directory")
                visible = false
                snapshotWith("")
                assertNull(vm.directoryPicker.value)
                assertEquals(1, browsers.single().closes)
                vm.close()
                assertEquals(1, ports.closes)
            }
        }
    }
    test("Unavailable and close remove controls and do not synthesize a draft") {
        val ports = SessionRenderSource()
        ports.state.value = SessionSettingsDataState.Unavailable
        withRenderer(ports) { vm, _ ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(32)) { SessionSettingsComponent(vm) }
                }
                assertTrue("No selected session" in snapshot, snapshot)
                assertFalse("Model behavior" in snapshot, snapshot)
                ports.publish()
                snapshotWith("Identity")
                vm.close()
                val closed = snapshotWith("No selected session")
                assertFalse("[Rename]" in closed, closed)
                assertFalse("[Browse]" in closed, closed)
            }
        }
    }
}

private suspend fun withRenderer(
    ports: SessionRenderSource,
    action: suspend (SessionSettingsViewModel, List<RenderBrowser>) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val browsers = mutableListOf<RenderBrowser>()
    val models = MutableStateFlow(listOf(
        ModelInfo(OpenAiModelId("catalog-a"), "a"), ModelInfo(OpenAiModelId("catalog-b"), "b"),
    ))
    val vm = createSessionSettingsViewModel(SessionSettingsDependencies(ports, models, {
        RenderBrowser(it).also(browsers::add)
    }), scope)
    try { action(vm, browsers) } finally { vm.close(); scope.cancel() }
}
private suspend fun TestMosaic<String>.snapshotWith(expected: String): String {
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
private suspend fun TestMosaic<String>.clickText(snapshot: String, text: String, offset: Int = 1) {
    val lines = snapshot.lines()
    val row = lines.indexOfFirst { text in it }
    assertTrue(row >= 0, snapshot)
    val column = lines[row].indexOf(text) + offset
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Press, MouseEvent.Button.Left))
    snapshotWith("")
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Release))
    snapshotWith("")
}
private class SessionRenderSource(kind: SessionSettingsTargetKind = SessionSettingsTargetKind.MaterializedSession) :
    SessionSettingsDataSource {
    private val initial = SessionSettingsSnapshot(0, kind, "Target", SessionSettingsConfiguration(
        OpenAiModelId("orphan"), Path("/original"), ReasoningEffort.Medium, ServiceTier.Default,
        RequestUserInputMode.AskUser,
    ), true)
    override val state = MutableStateFlow<SessionSettingsDataState>(SessionSettingsDataState.Available(initial))
    val writes = mutableListOf<Pair<Long, SessionSettingsConfiguration>>()
    val renames = mutableListOf<Pair<Long, String>>()
    var closes = 0
    fun publish(editable: Boolean = true, name: String = "Target") {
        val previous = (state.value as? SessionSettingsDataState.Available)?.snapshot ?: initial
        val candidate = previous.copy(editable = editable, sessionName = name)
        state.value = SessionSettingsDataState.Available(
            if (candidate == previous) candidate else candidate.copy(revision = previous.revision + 1),
        )
    }
    override suspend fun tryUpdateConfiguration(expectedRevision: Long, configuration: SessionSettingsConfiguration): Boolean {
        val current = (state.value as? SessionSettingsDataState.Available)?.snapshot ?: return false
        if (current.revision != expectedRevision || !current.editable) return false
        writes += expectedRevision to configuration
        state.value = SessionSettingsDataState.Available(
            current.copy(revision = current.revision + 1, configuration = configuration),
        )
        return true
    }
    override suspend fun tryRenameSession(expectedRevision: Long, sessionName: String): Boolean {
        val current = (state.value as? SessionSettingsDataState.Available)?.snapshot ?: return false
        if (current.revision != expectedRevision) return false
        renames += expectedRevision to sessionName
        state.value = SessionSettingsDataState.Available(current.copy(revision = current.revision + 1, sessionName = sessionName))
        return true
    }
    override fun close() { closes++ }
}
private class RenderBrowser(path: Path) : DirectoryPickerViewModel {
    override val state = MutableStateFlow(DirectoryPickerState(
        loadState = DirectoryPickerLoadState.Ready(1, path, path, listOf(Path(path, "Alpha"))),
    ))
    private val output = Channel<DirectoryPickerEffect>(Channel.BUFFERED)
    override val effects = output.receiveAsFlow()
    var closes = 0
    override fun navigateTo(directory: Path) = Unit
    override fun navigateUp() = Unit
    override fun retry() = Unit
    override fun updateFilter(query: String) { state.value = state.value.copy(filterQuery = query) }
    override fun clearFilter() { updateFilter("") }
    override fun confirm() { output.trySend(DirectoryPickerEffect.DirectorySelected(state.value.currentDirectory)) }
    override fun close() { closes++; output.close() }
}
