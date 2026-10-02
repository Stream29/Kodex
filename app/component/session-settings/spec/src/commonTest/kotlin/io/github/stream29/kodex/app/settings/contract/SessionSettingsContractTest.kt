package io.github.stream29.kodex.app.settings.contract

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path
import kotlin.test.*

val sessionSettingsContractTest by testSuite {
    val configuration = SessionSettingsConfiguration(OpenAiModelId("current"), Path("/cwd"), ReasoningEffort.Medium,
        ServiceTier.Default, RequestUserInputMode.AskUser)
    test("snapshot constructors enforce authoritative revision and name invariants") {
        assertFailsWith<IllegalArgumentException> {
            SessionSettingsSnapshot(-1, SessionSettingsTargetKind.MaterializedSession, "name", configuration, true)
        }
        assertFailsWith<IllegalArgumentException> {
            SessionSettingsSnapshot(0, SessionSettingsTargetKind.NewSessionDraft, "  ", configuration, true)
        }
        assertFailsWith<IllegalArgumentException> { SessionSettingsEffect.RenameSession(-1, "name") }
        assertFailsWith<IllegalArgumentException> { SessionSettingsEffect.RenameSession(0, " ") }
    }
    test("frontend options must be unique and include exact stored model") {
        val snapshot = SessionSettingsSnapshot(0, SessionSettingsTargetKind.MaterializedSession, "name", configuration, true)
        assertFailsWith<IllegalArgumentException> { SessionSettingsState.Available(snapshot, emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            SessionSettingsState.Available(snapshot, listOf(configuration.model, configuration.model))
        }
        assertEquals(snapshot, SessionSettingsState.Available(snapshot, listOf(configuration.model)).snapshot)
    }
    test("owned handles reject negative revision and retain reference identity") {
        val directory = object : WorkingDirectoryViewModel {
            override val picker: DirectoryPickerViewModel get() = error("This invariant test never renders a browser.")
            override val isActive = true
            override suspend fun select(directory: Path) = Unit
            override fun close() = Unit
        }
        val rename = object : SessionRenameViewModel {
            override val draftName = MutableStateFlow("name")
            override val isActive = true
            override fun updateDraftName(name: String) = Unit
            override suspend fun rename() = Unit
            override fun close() = Unit
        }
        assertFailsWith<IllegalArgumentException> { SessionWorkingDirectoryPicker(-1, directory) }
        assertFailsWith<IllegalArgumentException> { SessionSettingsRename(-1, rename) }
        assertNotEquals(SessionWorkingDirectoryPicker(0, directory), SessionWorkingDirectoryPicker(0, directory))
        assertNotEquals(SessionSettingsRename(0, rename), SessionSettingsRename(0, rename))
    }
}
