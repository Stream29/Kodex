package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationTabSelection
import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupTarget
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
            vm.withMountedCatalog { catalog ->
                val cwd = session.settings.value.cwd.toString()
                val row = catalog.state.first { it.pages[cwd]?.entries?.size == 1 }.pages.getValue(cwd).entries.single()
                catalog.toggleArchived(cwd)
                catalog.requestArchive(SessionGroupTarget(cwd, row))
                val archived = catalog.state.first { it.pages[cwd]?.entries?.singleOrNull()?.archived == true }
                    .pages.getValue(cwd).entries.single()
                vm.selectNewSession()
                catalog.requestOpen(SessionGroupTarget(cwd, archived))
                assertSame(session, vm.navigation.value.selectedSession)
                catalog.state.first { it.pages[cwd]?.entries?.singleOrNull()?.archived == false }
                assertSame(catalog, vm.sessionCatalog)
            }
        }
    }
    test("close and archive disposes the exact tab but retains backend state") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val session = vm.submitTestSession()
            assertTrue(vm.closeAndArchiveSession(session))
            assertEquals(PersistedSessionLifecycleState.Closed, session.lifecycle.value)
            vm.withMountedCatalog { catalog ->
                val cwd = catalog.state.value.groups!!.single().cwd
                catalog.toggleArchived(cwd)
                val row = catalog.state.first { it.pages[cwd]?.entries?.size == 1 }.pages.getValue(cwd).entries.single()
                assertTrue(row.archived)
                assertTrue(row.isActive)
            }
            assertFalse(vm.closeAndArchiveSession(session))
        }
    }
    test("popup dismissal compares the exact handle") {
        applicationFixture { app, _ ->
            val first = app.viewModel.openDeleteSessionPopup(91)
            val next = app.viewModel.openDeleteSessionPopup(92)
            assertFalse(app.viewModel.dismissPopup(first))
            assertSame(next, app.viewModel.popup.value)
            assertTrue(app.viewModel.dismissPopup(next))
            assertEquals(ApplicationPopupState.Closed, app.viewModel.popup.value)
        }
    }
    test("catalog navigation retains its stable child and never dismisses an unrelated popup") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val session = vm.submitTestSession()
            vm.selectNewSession()
            val popup = vm.openDeleteSessionPopup(92)
            vm.withMountedCatalog { catalog ->
                val cwd = session.settings.value.cwd.toString()
                val row = catalog.state.first { it.pages[cwd]?.entries?.size == 1 }.pages.getValue(cwd).entries.single()
                catalog.requestOpen(SessionGroupTarget(cwd, row))
                assertSame(session, vm.navigation.value.selectedSession)
                assertSame(catalog, vm.sessionCatalog)
                assertSame(popup, vm.popup.value)
            }
            assertNull(vm.sessionCatalog?.state?.value?.groups)
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
}
