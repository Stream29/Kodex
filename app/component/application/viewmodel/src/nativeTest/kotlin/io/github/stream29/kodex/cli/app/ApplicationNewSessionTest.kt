package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.ComposerLifecycle
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationTabSelection
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import io.github.stream29.kodex.app.settings.contract.SessionSettingsState
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.io.files.Path
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem as Fs
import kotlin.test.*

/** Exercises the actual production Application.kt factories over the isolated RPC host. */
val applicationNewSessionTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("production startup and selecting plus keep a backend-free stable singleton") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val singleton = vm.newSession
            assertTrue(vm.navigation.value.tabs.isEmpty())
            assertNull(vm.navigation.value.selectedSession)
            assertEquals(ApplicationTabSelection.NewSession, vm.navigation.value.selection)
            repeat(3) { vm.selectNewSession() }
            assertNull(vm.submitNewSession(singleton.composer.state.value.revision))
            val popup = vm.openSessionCatalogPopup()
            popup.viewModel.refresh()
            assertTrue(assertIs<SessionCatalogState.Loaded>(popup.viewModel.state.value).sessions.isEmpty())
            assertSame(singleton, vm.newSession)
            assertEquals(ComposerLifecycle.Open, singleton.composer.state.value.lifecycle)
        }
    }
    test("switching away and back retains text cwd and children then success resets the same singleton") {
        applicationFixture { app, home ->
            val vm = app.viewModel
            val tab = vm.submitTestSession()
            val singleton = vm.newSession
            val composer = singleton.composer
            val configuration = singleton.runtimeConfiguration
            val cwd = Path(home, "draft-cwd")
            Fs.createDirectories(cwd)
            singleton.updateWorkingDirectory(cwd)
            singleton.settings.first { it.cwd == cwd }
            composer.update(" \n unsent draft \n ", 4)
            val input = composer.state.value
            vm.selectTab(tab)
            vm.selectNewSession()
            assertSame(singleton, vm.newSession)
            assertSame(composer, singleton.composer)
            assertSame(configuration, singleton.runtimeConfiguration)
            assertEquals(input, composer.state.value)
            assertEquals(cwd, singleton.settings.value.cwd)
            val created = assertNotNull(vm.submitNewSession(input.revision))
            assertSame(created, vm.navigation.value.selectedSession)
            assertEquals(listOf(tab, created), vm.navigation.value.tabs)
            assertEquals(cwd, created.settings.value.cwd)
            assertEquals("", composer.state.value.text)
            singleton.settings.first { it.cwd == Fs.resolve(home) }
            assertEquals(ComposerLifecycle.Open, composer.state.value.lifecycle)
            assertFalse(configuration.state.value.closed)
        }
    }
    test("no-target Settings stays unavailable through later selection close and Login returnTo") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val settings = vm.openSettingsPopup(null, SettingsPage.NewSession)
            assertNull(settings.target)
            assertEquals(SettingsPage.NewSession, settings.viewModel.selectedPage.value)
            settings.viewModel.selectPage(SettingsPage.CurrentSession)
            assertEquals(SessionSettingsState.Unavailable, settings.viewModel.session.state.value)
            val tab = vm.submitTestSession()
            assertSame(settings, vm.popup.value)
            assertNull(settings.target)
            assertEquals(SessionSettingsState.Unavailable, settings.viewModel.session.state.value)
            val login = vm.openLoginPopup(settings)
            assertSame(settings, login.returnTo)
            assertNull(login.returnTo.target)
            val loginEffects = async(start = CoroutineStart.UNDISPATCHED) { login.viewModel.effects.firstOrNull() }
            try {
                assertFalse(loginEffects.isCompleted)
                assertTrue(vm.closeTab(tab))
                assertSame(login, vm.popup.value)
                assertFalse(loginEffects.isCompleted, "Closing an unrelated tab must not close Login's effects.")
                assertTrue(vm.dismissPopup(login))
                assertNull(loginEffects.await(), "Dismissing Login closes its effect channel without starting OAuth.")
            } finally { loginEffects.cancelAndJoin() }
            assertSame(settings, vm.popup.value)
            assertNull(settings.target)
            settings.viewModel.selectPage(SettingsPage.NewSession)
            assertTrue(settings.viewModel.newSession.state.value.active)
            assertTrue(vm.dismissPopup(settings))
            assertFalse(settings.viewModel.newSession.state.value.active)
        }
    }
    test("production defaults and singleton edit the same four fields without mutating persisted Session") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val tab = vm.submitTestSession()
            val original = tab.settings.value
            val settings = vm.openSettingsPopup(null, SettingsPage.NewSession)
            val page = settings.viewModel.newSession
            val singleton = vm.newSession
            singleton.updateModelConfiguration(OpenAiModelId("from-singleton"), ReasoningEffort.Max, ServiceTier.Fast)
            singleton.updateRequestUserInputMode(RequestUserInputMode.NoQuestion)
            val projected = page.state.first {
                it.settings.model == OpenAiModelId("from-singleton") &&
                    it.settings.requestUserInputMode == RequestUserInputMode.NoQuestion
            }
            assertEquals(ReasoningEffort.Max, projected.settings.reasoningEffort)
            assertEquals(ServiceTier.Fast, projected.settings.serviceTier)
            page.updateModel(projected.revision, OpenAiModelId("from-settings"))
            val model = page.state.first { it.settings.model == OpenAiModelId("from-settings") }
            page.updateReasoningEffort(model.revision, ReasoningEffort.High)
            val effort = page.state.first { it.settings.reasoningEffort == ReasoningEffort.High }
            page.updateServiceTier(effort.revision, ServiceTier.Ultrafast)
            val tier = page.state.first { it.settings.serviceTier == ServiceTier.Ultrafast }
            page.updateRequestUserInputMode(tier.revision, RequestUserInputMode.AskUser)
            singleton.settings.first {
                it.model == OpenAiModelId("from-settings") && it.reasoning.effort == ReasoningEffort.High &&
                    it.serviceTier == ServiceTier.Ultrafast && it.requestUserInputMode == RequestUserInputMode.AskUser
            }
            assertEquals(original, tab.settings.value)
            assertEquals(SessionSettingsState.Unavailable, settings.viewModel.session.state.value)
        }
    }
    test("shutdown releases singleton and no-target Login and Settings without stopping backend turn") {
        val running = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        applicationFixture(response = {
            running.complete(Unit)
            try {
                finish.await()
                emit(ResponsesStreamEvent.Completed(Response(id = "done", endTurn = true)))
            } finally { ended.complete(Unit) }
        }) { app, _ ->
            val vm = app.viewModel
            val singleton = vm.newSession
            singleton.composer.update("accepted")
            try {
                val tab = assertNotNull(vm.submitNewSession(singleton.composer.state.value.revision))
                running.await()
                val settings = vm.openSettingsPopup(null, SettingsPage.NewSession)
                val login = vm.openLoginPopup(settings)
                val loginEffects = async(start = CoroutineStart.UNDISPATCHED) { login.viewModel.effects.firstOrNull() }
                try {
                    assertFalse(loginEffects.isCompleted)
                    assertNotNull(tab.rootAgent.value).running.first { it }
                    vm.shutdown()
                    assertEquals(ComposerLifecycle.Closed, singleton.composer.state.value.lifecycle)
                    assertTrue(singleton.runtimeConfiguration.state.value.closed)
                    assertNull(loginEffects.await(), "Application shutdown closes Login's effect channel.")
                    assertFalse(settings.viewModel.newSession.state.value.active)
                    assertEquals(ApplicationPopupState.Closed, vm.popup.value)
                    assertNull(tab.rootAgent.value)
                    assertFalse(ended.isCompleted, "Frontend shutdown must not Stop the backend model turn.")
                    assertFailsWith<IllegalStateException> { singleton.composer.update("late") }
                } finally { loginEffects.cancelAndJoin() }
            } finally { finish.complete(Unit) }
        }
    }
}
