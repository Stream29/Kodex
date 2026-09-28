package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem as Fs
import kotlin.test.*

val sessionTreeCliViewModelTest by testSuite {
    test("materialization replaces only the exact draft slot") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val first = vm.navigation.value.selected
            vm.createNewSessionTab()
            val before = vm.navigation.value
            val persisted = vm.materializeNewSession(1)
            assertEquals(before.tabs.size, vm.navigation.value.tabs.size)
            assertSame(first, vm.navigation.value.tabs[0])
            assertSame(persisted, vm.navigation.value.tabs[1])
            assertEquals(before.selectedIndex, vm.navigation.value.selectedIndex)
        }
    }
    test("opening a persisted session reuses its tab and unarchives it") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val session = vm.materializeNewSession(0)
            val catalog = vm.openSessionCatalogPopup().viewModel
            catalog.archive(session.sessionIndex)
            catalog.setShowArchived(true)
            assertTrue(assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.single().archived)
            vm.createNewSessionTab()
            assertSame(session, vm.openSession(session.sessionIndex))
            catalog.refresh()
            assertFalse(assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.single().archived)
        }
    }
    test("close and archive disposes the exact tab but retains backend state") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val session = vm.materializeNewSession(0)
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
    test("login dismissal restores its settings parent and target closure disposes both") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val target = vm.navigation.value.selected
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
    test("working directory popup edits only its captured draft") {
        applicationFixture { app, home ->
            val vm = app.viewModel
            val first = assertIs<NewSessionViewModel>(vm.navigation.value.selected)
            val popup = vm.openWorkingDirectoryPopup(first)
            val second = vm.createNewSessionTab()
            val chosen = Path(home, "chosen")
            Fs.createDirectories(chosen)
            popup.viewModel.select(chosen)
            assertEquals(chosen, first.settings.value.cwd)
            assertEquals(Fs.resolve(home), second.settings.value.cwd)
            assertSame(first, popup.viewModel.target)
            assertTrue(vm.closeTab(first))
            assertEquals(ApplicationPopupState.Closed, vm.popup.value)
        }
    }
    test("child updates do not republish navigation and command replies need no optimistic cache") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val session = vm.materializeNewSession(0)
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
            val source = vm.materializeNewSession(0)
            val fork = vm.forkSession(source.sessionIndex)
            val selected = vm.navigation.value.selected
            vm.openCreatedSessions(listOf(fork, fork, source.sessionIndex))
            assertSame(selected, vm.navigation.value.selected)
            assertEquals(2, vm.navigation.value.tabs.size)
        }
    }
}
