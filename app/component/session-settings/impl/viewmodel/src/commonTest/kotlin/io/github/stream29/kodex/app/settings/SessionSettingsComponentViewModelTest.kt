@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.settings

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.pathpicker.contract.*
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.io.files.Path
import kotlin.test.*

val sessionSettingsComponentViewModelTest by testSuite {
    test("both target kinds project catalog order then missing current without revision changes") {
        for (kind in SessionSettingsTargetKind.entries) runTest {
            val source = SettingsSource(kind)
            val models = MutableStateFlow(listOf(model("z"), model("a"), model("z")))
            val vm = createSessionSettingsViewModel(SessionSettingsDependencies(source, models), this)
            try {
                val initial = assertIs<SessionSettingsState.Available>(vm.state.value)
                assertEquals(kind, initial.snapshot.targetKind)
                assertEquals(listOf("z", "a", "current"), initial.modelOptions.map { it.value })
                models.value = listOf(model("current"), model("b"))
                runCurrent()
                val next = assertIs<SessionSettingsState.Available>(vm.state.value)
                assertEquals(7L, next.snapshot.revision)
                assertEquals(listOf("current", "b"), next.modelOptions.map { it.value })
                assertTrue(source.writes.isEmpty())
            } finally { vm.close() }
            assertEquals(1, source.closes)
        }
    }
    test("four configuration commands freeze revision and all untouched fields in FIFO order") {
        runTest {
            val source = SettingsSource()
            val vm = createSessionSettingsViewModel(SessionSettingsDependencies(source, MutableStateFlow(emptyList())), this)
            try {
                vm.updateModel(7, OpenAiModelId("next"))
                vm.updateReasoningEffort(7, ReasoningEffort.Max)
                vm.updateServiceTier(7, ServiceTier.Flex)
                vm.updateRequestUserInputMode(7, RequestUserInputMode.NoQuestion)
                assertTrue(source.writes.isEmpty())
                runCurrent()
                assertEquals(listOf(
                    7L to source.configuration.copy(model = OpenAiModelId("next")),
                    7L to source.configuration.copy(reasoningEffort = ReasoningEffort.Max),
                    7L to source.configuration.copy(serviceTier = ServiceTier.Flex),
                    7L to source.configuration.copy(requestUserInputMode = RequestUserInputMode.NoQuestion),
                ), source.writes)
            } finally { vm.close() }
        }
    }
    test("stale unavailable and noneditable config admission never creates browsers or writes") {
        runTest {
            val source = SettingsSource()
            var creations = 0
            val vm = createSessionSettingsViewModel(SessionSettingsDependencies(source, MutableStateFlow(emptyList()), {
                creations++; SettingsBrowser(it)
            }), this)
            try {
                vm.updateModel(6, OpenAiModelId("wrong"))
                vm.requestWorkingDirectory(6)
                source.publish(editable = false)
                // Deliberately do not run the projection collector before command admission.
                vm.updateServiceTier(7, ServiceTier.Fast)
                vm.requestWorkingDirectory(7)
                source.state.value = SessionSettingsDataState.Unavailable
                vm.updateReasoningEffort(7, ReasoningEffort.High)
                vm.requestWorkingDirectory(7)
                vm.requestRename(7)
                runCurrent()
                assertEquals(0, creations)
                assertTrue(source.writes.isEmpty())
                assertNull(vm.rename.value)
                assertEquals(SessionSettingsState.Unavailable, vm.state.value)
            } finally { vm.close() }
        }
    }
    test("rename is owned by exact child and trims with revision-only not editable admission") {
        runTest {
            val source = SettingsSource()
            source.publish(editable = false)
            val vm = createSessionSettingsViewModel(SessionSettingsDependencies(source, MutableStateFlow(emptyList())), this)
            try {
                vm.requestRename(7)
                val first = assertNotNull(vm.rename.value)
                assertEquals("Original", first.viewModel.draftName.value)
                first.viewModel.updateDraftName("  Trimmed  ")
                first.viewModel.rename()
                assertTrue(source.renames.isEmpty())
                runCurrent()
                assertEquals(listOf(7L to "Trimmed"), source.renames)
                vm.requestRename(7)
                val replacement = assertNotNull(vm.rename.value)
                assertFalse(first.viewModel.isActive)
                assertFalse(vm.dismissRename(first))
                assertSame(replacement, vm.rename.value)
                assertFailsWith<IllegalStateException> { first.viewModel.rename() }
                replacement.viewModel.updateDraftName("   ")
                replacement.viewModel.rename()
                vm.renameSession(6, "stale")
                runCurrent()
                assertEquals(1, source.renames.size)
                assertTrue(vm.dismissRename(replacement))
            } finally { vm.close() }
        }
    }
    test("rename target revision is frozen even when source changes between admission and execution") {
        runTest {
            val source = SettingsSource()
            val vm = createSessionSettingsViewModel(SessionSettingsDependencies(source, MutableStateFlow(emptyList())), this)
            try {
                vm.requestRename(7)
                val handle = assertNotNull(vm.rename.value)
                handle.viewModel.updateDraftName("queued")
                handle.viewModel.rename()
                source.publish(revision = 8)
                runCurrent()
                assertEquals(listOf(7L to "queued"), source.renames)
                handle.viewModel.updateDraftName("late")
                handle.viewModel.rename()
                runCurrent()
                assertEquals(1, source.renames.size)
            } finally { vm.close() }
        }
    }
    test("directory replacement owns browser and stale handle cannot consume replacement") {
        runTest {
            val source = SettingsSource()
            val browsers = mutableListOf<SettingsBrowser>()
            val vm = createSessionSettingsViewModel(SessionSettingsDependencies(source, MutableStateFlow(emptyList()), {
                SettingsBrowser(it).also(browsers::add)
            }), this)
            try {
                vm.requestWorkingDirectory(7)
                val first = assertNotNull(vm.directoryPicker.value)
                vm.requestWorkingDirectory(7)
                val next = assertNotNull(vm.directoryPicker.value)
                assertSame(browsers[1], next.selection.picker)
                assertEquals(1, browsers[0].closes)
                assertFalse(vm.selectWorkingDirectory(first, Path("/late")))
                assertFalse(vm.dismissWorkingDirectoryPicker(first))
                assertSame(next, vm.directoryPicker.value)
                next.selection.select(Path("/chosen"))
                assertNull(vm.directoryPicker.value)
                assertEquals(1, browsers[1].closes)
                runCurrent()
                assertEquals(listOf(7L to source.configuration.copy(workingDirectory = Path("/chosen"))), source.writes)
            } finally { vm.close() }
        }
    }
    test("directory boolean is exact handle consumption even when revision target or editability expires") {
        for (expired in listOf("revision", "target", "editable")) runTest {
            val source = SettingsSource()
            val vm = createSessionSettingsViewModel(SessionSettingsDependencies(source, MutableStateFlow(emptyList()), { SettingsBrowser(it) }), this)
            try {
                vm.requestWorkingDirectory(7)
                val handle = assertNotNull(vm.directoryPicker.value)
                when (expired) {
                    "revision" -> source.publish(revision = 8)
                    "target" -> source.state.value = SessionSettingsDataState.Unavailable
                    else -> source.publish(editable = false)
                }
                assertTrue(vm.selectWorkingDirectory(handle, Path("/ignored")))
                assertFalse(vm.selectWorkingDirectory(handle, Path("/twice")))
                assertFalse(handle.selection.isActive)
                runCurrent()
                assertTrue(source.writes.isEmpty())
            } finally { vm.close() }
        }
    }
    test("null browser factory preserves old child while thrown failure is observable") {
        runTest {
            val source = SettingsSource()
            var result: DirectoryPickerViewModel? = SettingsBrowser(Path("/initial"))
            var failure: Throwable? = null
            val vm = createSessionSettingsViewModel(SessionSettingsDependencies(source, MutableStateFlow(emptyList()), {
                failure?.let { throw it }; result
            }), this)
            try {
                vm.requestWorkingDirectory(7)
                val original = assertNotNull(vm.directoryPicker.value)
                result = null
                vm.requestWorkingDirectory(7)
                assertSame(original, vm.directoryPicker.value)
                failure = IllegalStateException("factory")
                assertFailsWith<IllegalStateException> { vm.requestWorkingDirectory(7) }
                assertSame(original, vm.directoryPicker.value)
            } finally { vm.close() }
        }
    }
    test("hide disposes both types of unaccepted child but observes and preserves admitted commands") {
        runTest {
            val source = SettingsSource()
            val vm = createSessionSettingsViewModel(SessionSettingsDependencies(source, MutableStateFlow(emptyList()), { SettingsBrowser(it) }), this)
            try {
                vm.requestRename(7)
                val rename = assertNotNull(vm.rename.value)
                vm.requestWorkingDirectory(7)
                assertFalse(rename.viewModel.isActive)
                val directory = assertNotNull(vm.directoryPicker.value)
                vm.updateServiceTier(7, ServiceTier.Fast)
                vm.hidePage()
                assertFalse(directory.selection.isActive)
                assertNull(vm.rename.value)
                assertNull(vm.directoryPicker.value)
                source.publish(revision = 8)
                runCurrent()
                assertEquals(8L, assertIs<SessionSettingsState.Available>(vm.state.value).snapshot.revision)
                assertEquals(1, source.writes.size)
                assertEquals(0, source.closes)
            } finally { vm.close() }
        }
    }
    test("ordinary failures report frozen cwd once and do not block the next command") {
        runTest {
            val source = SettingsSource()
            val failures = mutableListOf<Pair<Throwable, Path>>()
            val expected = IllegalStateException("fake")
            source.onWrite = { throw expected }
            val vm = createSessionSettingsViewModel(SessionSettingsDependencies(source, MutableStateFlow(emptyList()),
                reportUnhandledError = { error, cwd -> failures += error to cwd }), this)
            try {
                vm.updateServiceTier(7, ServiceTier.Fast)
                source.publish(revision = 8, cwd = Path("/new"))
                runCurrent()
                assertEquals(listOf<Pair<Throwable, Path>>(expected to Path("/initial")), failures)
                source.onWrite = {}
                vm.renameSession(8, "next")
                runCurrent()
                assertEquals(listOf(8L to "next"), source.renames)
            } finally { vm.close() }
        }
    }
    test("public close cancels pending retry and queued rename without drain or failure report") {
        runTest {
            val source = SettingsSource()
            var cancelled = false
            var reports = 0
            source.onWrite = { try { awaitCancellation() } finally { cancelled = true } }
            val vm = createSessionSettingsViewModel(SessionSettingsDependencies(source, MutableStateFlow(emptyList()),
                reportUnhandledError = { _, _ -> reports++ }), this)
            vm.updateServiceTier(7, ServiceTier.Fast)
            vm.renameSession(7, "queued")
            runCurrent()
            vm.close()
            vm.close()
            vm.renameSession(7, "closed")
            runCurrent()
            assertTrue(cancelled)
            assertTrue(source.renames.isEmpty())
            assertEquals(0, reports)
            assertEquals(1, source.closes)
            assertEquals(SessionSettingsState.Unavailable, vm.state.value)
        }
    }
    test("source false is ordinary rejection; source cancellation stops pending commands without business failure") {
        runTest {
            val source = SettingsSource()
            var reports = 0
            source.result = false
            val vm = createSessionSettingsViewModel(SessionSettingsDependencies(source, MutableStateFlow(emptyList()),
                reportUnhandledError = { _, _ -> reports++ }), this)
            try {
                vm.updateModel(7, OpenAiModelId("rejected"))
                runCurrent()
                assertEquals(0, reports)
                assertIs<SessionSettingsState.Available>(vm.state.value)
                source.onWrite = { throw CancellationException("expired exact binding") }
                vm.updateServiceTier(7, ServiceTier.Fast)
                vm.renameSession(7, "must not run")
                runCurrent()
                assertTrue(source.renames.isEmpty())
                assertEquals(0, reports)
                assertEquals(1, source.closes)
                assertEquals(SessionSettingsState.Unavailable, vm.state.value)
            } finally { vm.close() }
        }
    }
    test("owner cancellation explicitly releases source and exact children") {
        runTest {
            val owner = Job()
            val source = SettingsSource()
            val vm = createSessionSettingsViewModel(SessionSettingsDependencies(source, MutableStateFlow(emptyList())),
                CoroutineScope(coroutineContext + owner))
            vm.requestRename(7)
            val handle = assertNotNull(vm.rename.value)
            runCurrent()
            owner.cancel()
            runCurrent()
            assertFalse(handle.viewModel.isActive)
            assertEquals(1, source.closes)
            assertEquals(SessionSettingsState.Unavailable, vm.state.value)
        }
    }
    test("public close disposes the exact rename child without dispatching a rename") {
        runTest {
            val source = SettingsSource()
            val vm = createSessionSettingsViewModel(SessionSettingsDependencies(source, MutableStateFlow(emptyList())), this)
            vm.requestRename(7)
            val handle = assertNotNull(vm.rename.value)
            vm.close()
            assertFalse(handle.viewModel.isActive)
            assertNull(vm.rename.value)
            assertTrue(source.renames.isEmpty())
        }
    }
}

private fun model(id: String) = ModelInfo(OpenAiModelId(id), id)
private class SettingsSource(kind: SessionSettingsTargetKind = SessionSettingsTargetKind.MaterializedSession) :
    SessionSettingsDataSource {
    val configuration = SessionSettingsConfiguration(
        OpenAiModelId("current"), Path("/initial"), ReasoningEffort.Medium, ServiceTier.Default,
        RequestUserInputMode.AskUser,
    )
    private val initial = SessionSettingsSnapshot(7, kind, "Original", configuration, true)
    override val state = MutableStateFlow<SessionSettingsDataState>(SessionSettingsDataState.Available(initial))
    val writes = mutableListOf<Pair<Long, SessionSettingsConfiguration>>()
    val renames = mutableListOf<Pair<Long, String>>()
    var closes = 0
    var result = true
    var onWrite: suspend () -> Unit = {}
    fun publish(revision: Long = 7, editable: Boolean = true, cwd: Path = Path("/initial")) {
        state.value = SessionSettingsDataState.Available(
            initial.copy(revision = revision, editable = editable, configuration = configuration.copy(workingDirectory = cwd)),
        )
    }
    override suspend fun tryUpdateConfiguration(expectedRevision: Long, configuration: SessionSettingsConfiguration): Boolean {
        writes += expectedRevision to configuration
        onWrite()
        return result
    }
    override suspend fun tryRenameSession(expectedRevision: Long, sessionName: String): Boolean {
        renames += expectedRevision to sessionName
        return true
    }
    override fun close() { closes++ }
}
private class SettingsBrowser(path: Path) : DirectoryPickerViewModel {
    override val state = MutableStateFlow(DirectoryPickerState(
        loadState = DirectoryPickerLoadState.Ready(1, path, path, emptyList()),
    ))
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
