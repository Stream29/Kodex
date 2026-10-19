package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationTabSelection
import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem as Fs
import kotlin.test.*

val sessionTreeCliViewModelTest by testSuite {
    test("submission appends and selects a persisted tab while retaining the singleton") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val first = vm.submitTestSession()
            val target = vm.newSession
            val before = vm.navigation.value
            val persisted = vm.submitTestSession("second")
            assertEquals(before.tabs.size + 1, vm.navigation.value.tabs.size)
            assertSame(first, vm.navigation.value.tabs[0])
            assertSame(persisted, vm.navigation.value.tabs[1])
            assertSame(persisted, vm.navigation.value.selectedSession)
            assertSame(target, vm.newSession)
        }
    }
    test("opening a persisted session reuses its tab and unarchives it") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val session = vm.submitTestSession()
            val catalog = vm.openSessionCatalogPopup().viewModel
            catalog.archive(session.sessionIndex)
            catalog.setShowArchived(true)
            assertTrue(assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.single().archived)
            vm.selectNewSession()
            assertSame(session, vm.openSession(session.sessionIndex))
            catalog.refresh()
            assertFalse(assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.single().archived)
        }
    }
    test("close and archive disposes the exact tab but retains backend state") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val session = vm.submitTestSession()
            assertTrue(vm.closeAndArchiveSession(session))
            assertEquals(PersistedSessionLifecycleState.Closed, session.lifecycle.value)
            val catalog = vm.openSessionCatalogPopup().viewModel
            catalog.setShowArchived(true)
            val row = assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.single()
            assertTrue(row.archived)
            assertTrue(row.isActive)
            assertFalse(vm.closeAndArchiveSession(session))
        }
    }
    test("popup dismissal compares the exact handle") {
        applicationFixture { app, _ ->
            val first = app.viewModel.openSessionCatalogPopup()
            val next = app.viewModel.openSessionCatalogPopup()
            assertFalse(app.viewModel.dismissPopup(first))
            assertSame(next, app.viewModel.popup.value)
            assertTrue(app.viewModel.dismissPopup(next))
            assertEquals(ApplicationPopupState.Closed, app.viewModel.popup.value)
        }
    }
    test("catalog navigation dismisses its exact opening and stale callbacks cannot dismiss its replacement") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val session = vm.submitTestSession()
            val first = vm.openSessionCatalogPopup()
            first.viewModel.refresh()
            val row = assertIs<SessionCatalogState.Loaded>(first.viewModel.state.value).sessions.single()
            first.viewModel.requestOpen(row)
            assertSame(session, vm.navigation.value.selectedSession)
            assertEquals(ApplicationPopupState.Closed, vm.popup.value)
            val replacement = vm.openSessionCatalogPopup()
            assertFailsWith<CancellationException> { first.viewModel.dismiss() }
            assertSame(replacement, vm.popup.value)
            assertFalse(vm.dismissPopup(first))
        }
    }
    test("login dismissal restores its settings parent and target closure disposes both") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val target = vm.submitTestSession()
            val settings = vm.openSettingsPopup(target, SettingsPage.OpenAi)
            val login = vm.openLoginPopup(settings)
            assertSame(settings, login.returnTo)
            assertTrue(vm.dismissPopup(login))
            assertSame(settings, vm.popup.value)
            val second = vm.openLoginPopup(settings)
            assertTrue(vm.closeTab(target))
            assertEquals(ApplicationPopupState.Closed, vm.popup.value)
            assertFalse(vm.dismissPopup(second))
        }
    }
    test("working directory popup retains the singleton target while a persisted tab is selected") {
        applicationFixture { app, home ->
            val vm = app.viewModel
            val second = vm.submitTestSession()
            val first = vm.newSession
            vm.selectNewSession()
            val popup = vm.openWorkingDirectoryPopup(first)
            vm.selectTab(second)
            val chosen = Path(home, "chosen")
            Fs.createDirectories(chosen)
            popup.viewModel.select(chosen)
            first.settings.first { it.cwd == chosen }
            assertEquals(chosen, first.settings.value.cwd)
            assertEquals(Fs.resolve(home), second.settings.value.cwd)
            assertSame(first, popup.viewModel.target)
            assertTrue(vm.closeTab(second))
            assertSame(popup, vm.popup.value)
            assertEquals(ApplicationTabSelection.NewSession, vm.navigation.value.selection)
            assertTrue(vm.dismissPopup(popup))
        }
    }
    test("child updates do not republish navigation and command replies need no optimistic cache") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val session = vm.submitTestSession()
            val agent = requireNotNull(session.rootAgent.value)
            val snapshot = vm.navigation.value
            var emissions = 0
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { vm.navigation.collect { emissions++ } }
            try {
                repeat(128) { agent.composer.update("draft-$it", 0) }
                session.rename("renamed")
                session.name.first { it == "renamed" }
                assertSame(snapshot, vm.navigation.value)
                assertEquals(1, emissions)
                assertEquals(128, agent.composer.state.value.revision)
            } finally { collector.cancelAndJoin() }
        }
    }
    test("already-created child tabs are deduplicated without changing selection") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val source = vm.submitTestSession()
            val fork = vm.forkSession(source.sessionIndex)
            val selected = vm.navigation.value.selectedSession
            vm.openCreatedSessions(listOf(fork, fork, source.sessionIndex))
            assertSame(selected, vm.navigation.value.selectedSession)
            assertEquals(2, vm.navigation.value.tabs.size)
            vm.selectNewSession()
            vm.newSession.composer.update("retain while opening background sessions")
            val input = vm.newSession.composer.state.value
            val next = vm.forkSession(source.sessionIndex)
            vm.openCreatedSessions(listOf(next, fork, next))
            assertEquals(ApplicationTabSelection.NewSession, vm.navigation.value.selection)
            assertNull(vm.navigation.value.selectedSession)
            assertEquals(input, vm.newSession.composer.state.value)
            assertEquals(3, vm.navigation.value.tabs.size)
        }
    }
}
