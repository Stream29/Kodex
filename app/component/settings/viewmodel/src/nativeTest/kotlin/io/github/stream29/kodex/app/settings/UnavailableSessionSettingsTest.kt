@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.settings

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.test.withRpcFrontend
import io.github.stream29.kodex.cli.rpc.*
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.*

val unavailableSessionSettingsTest by testSuite {
    test("no-target source always rejects writes and repeated close has no effect") {
        runTest {
            val source = UnavailableSessionSettingsDataSource
            val state = source.state
            val configuration = SessionSettingsConfiguration(
                OpenAiModelId("unused"), Path("/unused"), ReasoningEffort.High,
                ServiceTier.Fast, RequestUserInputMode.NoQuestion,
            )
            repeat(3) {
                assertSame(state, source.state)
                assertEquals(SessionSettingsDataState.Unavailable, state.value)
                for (revision in listOf(-1L, 0L, Long.MAX_VALUE)) {
                    assertFalse(source.tryUpdateConfiguration(revision, configuration))
                    assertFalse(source.tryRenameSession(revision, "unused"))
                    assertFalse(source.tryRenameSession(revision, ""))
                }
                source.close()
            }
            assertEquals(SessionSettingsDataState.Unavailable, state.value)
        }
    }
    test("no-target components never admit editors and share the source without shared disposal") {
        runTest {
            val models = MutableStateFlow<List<ModelInfo>>(emptyList())
            val dependencies = SessionSettingsDependencies(
                UnavailableSessionSettingsDataSource, models,
                createDirectoryPicker = { error("An unavailable editor cannot browse.") },
                reportUnhandledError = { _, _ -> error("Rejection is not an operation failure.") },
            )
            val first = createSessionSettingsViewModel(dependencies, this)
            val second = createSessionSettingsViewModel(dependencies, this)
            try {
                first.updateModel(0, OpenAiModelId("ignored"))
                first.updateReasoningEffort(0, ReasoningEffort.Max)
                first.updateServiceTier(0, ServiceTier.Flex)
                first.updateRequestUserInputMode(0, RequestUserInputMode.NoQuestion)
                first.requestWorkingDirectory(0)
                first.requestRename(0)
                first.renameSession(0, "ignored")
                models.value = listOf(ModelInfo(OpenAiModelId("catalog"), "Catalog"))
                runCurrent()
                assertEquals(SessionSettingsState.Unavailable, first.state.value)
                assertNull(first.directoryPicker.value)
                assertNull(first.rename.value)
                first.close()
                first.close()
                second.requestRename(0)
                second.requestWorkingDirectory(0)
                runCurrent()
                assertEquals(SessionSettingsState.Unavailable, second.state.value)
                assertNull(second.rename.value)
                assertNull(second.directoryPicker.value)
                assertEquals(SessionSettingsDataState.Unavailable, dependencies.source.state.value)
            } finally { first.close(); second.close() }
        }
    }
    test("nullable Settings inputs keep Current Session unavailable while shared defaults remain editable") {
        withRpcFrontend {
            val arguments = SettingsViewModelArguments(target = null, initialPage = SettingsPage.CurrentSession)
            val settings = RpcGlobalSettings.open(services.global, this, 120)
            val editor = RpcGlobalEditor(settings, services.global, this)
            val session = createSessionSettingsViewModel(
                SessionSettingsDependencies(UnavailableSessionSettingsDataSource, models), this,
            )
            val defaults = RpcNewSessionSettings(settings, this)
            val vm = createSettingsViewModel(arguments.initialPage, editor, session, defaults)
            try {
                assertNull(arguments.target)
                assertEquals(SettingsPage.CurrentSession, vm.selectedPage.value)
                assertEquals(SessionSettingsState.Unavailable, vm.session.state.value)
                val persisted = create("real target is unrelated")
                val original = persisted.settings.value
                val title = settings.settings.value.sessionTitle
                vm.session.renameSession(0, "must not retarget")
                vm.session.updateServiceTier(0, ServiceTier.Fast)
                vm.session.requestRename(0)
                vm.session.requestWorkingDirectory(0)
                vm.selectPage(SettingsPage.NewSession)
                val before = defaults.state.value
                val tier = if (before.settings.serviceTier == ServiceTier.Flex) ServiceTier.Fast else ServiceTier.Flex
                vm.newSession.updateServiceTier(before.revision, tier)
                settings.settings.first { it.newSession.serviceTier == tier }
                assertEquals(title, settings.settings.value.sessionTitle)
                assertSame(session, vm.session)
                assertEquals(SessionSettingsState.Unavailable, session.state.value)
                assertNull(session.rename.value)
                assertNull(session.directoryPicker.value)
                assertEquals(original, persisted.settings.value)
                vm.selectPage(SettingsPage.CurrentSession)
                assertEquals(SessionSettingsState.Unavailable, vm.session.state.value)
                assertNull(arguments.target)
            } finally { vm.close(); settings.close(); settings.join() }
        }
    }
}
