package io.github.stream29.kodex.cli.settings

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.io.files.Path
import kotlin.test.*

val sessionRenameAdapterTest by testSuite {
    test("binds the exact Settings source and effect revision, not a later snapshot") {
        val source = RenameSettingsSource()
        val other = RenameSettingsSource()
        val first = createSessionSettingsRenameChild(source, SessionSettingsEffect.RenameSession(7, "first"))
        val second = createSessionSettingsRenameChild(other, SessionSettingsEffect.RenameSession(9, "second"))
        try {
            first.updateDraftName("  edited  ")
            first.rename()
            assertEquals(listOf(7L to "edited"), source.requests)
            assertTrue(other.requests.isEmpty())
            second.rename()
            assertEquals(listOf(9L to "second"), other.requests)
            // The source enqueues a write; a normal return does not wait for a new snapshot.
            assertEquals(SessionSettingsState.Unavailable, source.state.value)
        } finally {
            first.close()
            second.close()
        }
        assertFalse(source.closed)
        assertFalse(other.closed)
    }
    test("disposed rename cannot dispatch a late write to its Settings source") {
        val source = RenameSettingsSource()
        val child = createSessionSettingsRenameChild(source, SessionSettingsEffect.RenameSession(7, "first"))
        child.close()
        child.updateDraftName("late")
        assertFailsWith<IllegalStateException> { child.rename() }
        assertTrue(source.requests.isEmpty())
        assertFalse(source.closed)
    }
}

private class RenameSettingsSource : SessionSettingsViewModel {
    override val state = MutableStateFlow<SessionSettingsState>(SessionSettingsState.Unavailable)
    override val directoryPicker = MutableStateFlow<SessionWorkingDirectoryPicker?>(null)
    override val effects = emptyFlow<SessionSettingsEffect>()
    val requests = mutableListOf<Pair<Long, String>>()
    var closed = false
    override fun renameSession(expectedRevision: Long, sessionName: String) {
        requests += expectedRevision to sessionName
    }
    override fun updateModel(expectedRevision: Long, model: OpenAiModelId) = Unit
    override fun updateReasoningEffort(expectedRevision: Long, reasoningEffort: ReasoningEffort) = Unit
    override fun updateServiceTier(expectedRevision: Long, serviceTier: ServiceTier) = Unit
    override fun updateRequestUserInputMode(expectedRevision: Long, mode: RequestUserInputMode) = Unit
    override fun requestWorkingDirectory(expectedRevision: Long) = Unit
    override fun selectWorkingDirectory(expected: SessionWorkingDirectoryPicker, workingDirectory: Path) = false
    override fun dismissWorkingDirectoryPicker(expected: SessionWorkingDirectoryPicker) = false
    override fun requestRename(expectedRevision: Long) = Unit
    override fun close() { closed = true }
}
