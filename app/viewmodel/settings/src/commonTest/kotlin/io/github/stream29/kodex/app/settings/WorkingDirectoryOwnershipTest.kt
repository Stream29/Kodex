@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.settings

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.pathpicker.contract.*
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.*

val workingDirectoryOwnershipTest by testSuite {
    test("replacement closes the old selection and stale handles cannot consume the new one") {
        runTest {
            val source = DirectorySettingsSource()
            val browsers = mutableListOf<SettingsBrowser>()
            val editor = createSessionSettingsViewModel(source, MutableStateFlow(emptyList()), this, {
                SettingsBrowser(it).also(browsers::add)
            })
            try {
                editor.requestWorkingDirectory(7)
                val old = assertNotNull(editor.directoryPicker.value)
                editor.requestWorkingDirectory(7)
                val current = assertNotNull(editor.directoryPicker.value)
                assertNotSame(old, current)
                assertSame(browsers[1], current.viewModel)
                assertEquals(7L, current.expectedRevision)
                assertFalse(old.selection.isActive)
                assertEquals(1, browsers[0].closes)
                assertFalse(editor.selectWorkingDirectory(old, Path("/late")))
                assertFalse(editor.dismissWorkingDirectoryPicker(old))
                assertSame(current, editor.directoryPicker.value)
                assertTrue(current.selection.isActive)
                assertTrue(source.writes.isEmpty())
            } finally { editor.close() }
        }
    }
    test("selection consumes its exact handle and browser before queued configuration write") {
        runTest {
            val source = DirectorySettingsSource()
            val browser = SettingsBrowser(Path("/initial"))
            val editor = createSessionSettingsViewModel(source, MutableStateFlow(emptyList()), this, { browser })
            try {
                editor.requestWorkingDirectory(7)
                val handle = assertNotNull(editor.directoryPicker.value)
                source.onWrite = {
                    assertNull(editor.directoryPicker.value)
                    assertFalse(handle.selection.isActive)
                    assertEquals(1, browser.closes)
                }
                handle.selection.select(Path("/selected"))
                assertNull(editor.directoryPicker.value)
                assertEquals(1, browser.closes)
                runCurrent()
                assertEquals(listOf(7L to source.configuration.copy(workingDirectory = Path("/selected"))), source.writes)
            } finally { editor.close() }
        }
    }
    test("a revision changed after opening is consumed without a stale configuration write") {
        runTest {
            val source = DirectorySettingsSource()
            val editor = createSessionSettingsViewModel(source, MutableStateFlow(emptyList()), this, { SettingsBrowser(it) })
            try {
                editor.requestWorkingDirectory(7)
                val handle = assertNotNull(editor.directoryPicker.value)
                source.publish(revision = 8)
                runCurrent()
                handle.selection.select(Path("/stale"))
                runCurrent()
                assertNull(editor.directoryPicker.value)
                assertFalse(handle.selection.isActive)
                assertTrue(source.writes.isEmpty())
            } finally { editor.close() }
        }
    }
    test("target loss after opening closes the selected child without retargeting") {
        runTest {
            val source = DirectorySettingsSource()
            val editor = createSessionSettingsViewModel(source, MutableStateFlow(emptyList()), this, { SettingsBrowser(it) })
            try {
                editor.requestWorkingDirectory(7)
                val handle = assertNotNull(editor.directoryPicker.value)
                source.state.value = SessionSettingsDataState.Unavailable
                runCurrent()
                assertTrue(editor.selectWorkingDirectory(handle, Path("/lost")))
                runCurrent()
                assertFalse(handle.selection.isActive)
                assertTrue(source.writes.isEmpty())
            } finally { editor.close() }
        }
    }
    test("stale noneditable and unavailable requests never construct a child") {
        runTest {
            val source = DirectorySettingsSource()
            var creations = 0
            val editor = createSessionSettingsViewModel(source, MutableStateFlow(emptyList()), this, {
                creations++
                SettingsBrowser(it)
            })
            try {
                editor.requestWorkingDirectory(6)
                source.publish(editable = false)
                runCurrent()
                editor.requestWorkingDirectory(7)
                source.state.value = SessionSettingsDataState.Unavailable
                runCurrent()
                editor.requestWorkingDirectory(7)
                assertNull(editor.directoryPicker.value)
                assertEquals(0, creations)
            } finally { editor.close() }
        }
    }
    test("dismiss and owner close release children without configuration writes") {
        runTest {
            val source = DirectorySettingsSource()
            val browsers = mutableListOf<SettingsBrowser>()
            val editor = createSessionSettingsViewModel(source, MutableStateFlow(emptyList()), this, {
                SettingsBrowser(it).also(browsers::add)
            })
            editor.requestWorkingDirectory(7)
            val first = assertNotNull(editor.directoryPicker.value)
            assertTrue(editor.dismissWorkingDirectoryPicker(first))
            assertFalse(editor.dismissWorkingDirectoryPicker(first))
            editor.requestWorkingDirectory(7)
            val last = assertNotNull(editor.directoryPicker.value)
            editor.close()
            editor.close()
            runCurrent()
            assertFalse(last.selection.isActive)
            assertEquals(listOf(1, 1), browsers.map { it.closes })
            assertTrue(source.writes.isEmpty())
            assertTrue(source.closed)
        }
    }
}

private class DirectorySettingsSource : SessionSettingsDataSource {
    val configuration = SessionSettingsConfiguration(
        OpenAiModelId("test"), Path("/initial"), ReasoningEffort.High, ServiceTier.Default, RequestUserInputMode.AskUser,
    )
    override val state = MutableStateFlow<SessionSettingsDataState>(snapshot())
    val writes = mutableListOf<Pair<Long, SessionSettingsConfiguration>>()
    var onWrite: () -> Unit = {}
    var closed = false
    fun publish(revision: Long = 7, editable: Boolean = true) { state.value = snapshot(revision, editable) }
    private fun snapshot(revision: Long = 7, editable: Boolean = true) = SessionSettingsDataState.Available(
        SessionSettingsSnapshot(revision, SessionSettingsTargetKind.NewSessionDraft, "captured", configuration, editable),
    )
    override suspend fun tryUpdateConfiguration(expectedRevision: Long, configuration: SessionSettingsConfiguration): Boolean {
        onWrite()
        writes += expectedRevision to configuration
        return false
    }
    override suspend fun tryRenameSession(expectedRevision: Long, sessionName: String) = false
    override fun close() { closed = true }
}

private class SettingsBrowser(directory: Path) : DirectoryPickerViewModel {
    override val state = MutableStateFlow(DirectoryPickerState(loadState = DirectoryPickerLoadState.Loading(1, directory)))
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
