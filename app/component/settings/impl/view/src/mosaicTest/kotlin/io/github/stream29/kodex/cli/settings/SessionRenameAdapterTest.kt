@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.cli.settings

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.createSessionSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.io.files.Path
import kotlin.test.*

/** The old renderer adapter is gone: verify the real component owns its exact rename child. */
val sessionRenameAdapterTest by testSuite {
    test("binds exact Settings source and captured revision, not another target") {
        runTest {
            val source = RenameSettingsSource(7, "first")
            val other = RenameSettingsSource(9, "second")
            val first = createSessionSettingsViewModel(SessionSettingsDependencies(source, MutableStateFlow(emptyList())), this)
            val second = createSessionSettingsViewModel(SessionSettingsDependencies(other, MutableStateFlow(emptyList())), this)
            try {
                first.requestRename(7)
                second.requestRename(9)
                val firstChild = assertNotNull(first.rename.value).viewModel
                val secondChild = assertNotNull(second.rename.value).viewModel
                firstChild.updateDraftName("  edited  ")
                firstChild.rename()
                runCurrent()
                assertEquals(listOf(7L to "edited"), source.requests)
                assertTrue(other.requests.isEmpty())
                secondChild.rename()
                runCurrent()
                assertEquals(listOf(9L to "second"), other.requests)
            } finally { first.close(); second.close() }
            assertTrue(source.closed)
            assertTrue(other.closed)
        }
    }
    test("dismissed exact rename cannot dispatch late writes or close its Settings source") {
        runTest {
            val source = RenameSettingsSource(7, "first")
            val parent = createSessionSettingsViewModel(SessionSettingsDependencies(source, MutableStateFlow(emptyList())), this)
            try {
                parent.requestRename(7)
                val handle = assertNotNull(parent.rename.value)
                assertTrue(parent.dismissRename(handle))
                handle.viewModel.updateDraftName("late")
                assertFailsWith<IllegalStateException> { handle.viewModel.rename() }
                runCurrent()
                assertTrue(source.requests.isEmpty())
                assertFalse(source.closed)
            } finally { parent.close() }
        }
    }
}

private class RenameSettingsSource(revision: Long, name: String) : SessionSettingsDataSource {
    override val state = MutableStateFlow<SessionSettingsDataState>(SessionSettingsDataState.Available(
        SessionSettingsSnapshot(revision, SessionSettingsTargetKind.MaterializedSession, name,
            SessionSettingsConfiguration(OpenAiModelId("test"), Path("."), ReasoningEffort.High,
                ServiceTier.Default, RequestUserInputMode.AskUser), editable = true),
    ))
    val requests = mutableListOf<Pair<Long, String>>()
    var closed = false
    override suspend fun tryRenameSession(expectedRevision: Long, sessionName: String): Boolean {
        requests += expectedRevision to sessionName
        return true
    }
    override suspend fun tryUpdateConfiguration(expectedRevision: Long, configuration: SessionSettingsConfiguration) = true
    override fun close() { closed = true }
}
