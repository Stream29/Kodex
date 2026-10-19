package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationTabSelection
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import kotlin.test.*

val sessionTreeCliSessionDeletionTest by testSuite {
    test("deleting through the catalog removes data tab and target popup exactly once") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val session = vm.submitTestSession()
            val popup = vm.openDeleteSessionPopup(session.sessionIndex)
            assertTrue(popup.viewModel.delete())
            assertEquals(ApplicationPopupState.Closed, vm.popup.value)
            assertTrue(vm.navigation.value.tabs.isEmpty())
            assertEquals(ApplicationTabSelection.NewSession, vm.navigation.value.selection)
            val catalog = vm.openSessionCatalogPopup().viewModel
            catalog.refresh()
            assertTrue(assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.isEmpty())
            assertFalse(vm.deleteSession(session.sessionIndex))
        }
    }
    test("delete preserves the selected surviving instance then chooses successor and last item") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val preceding = vm.submitTestSession()
            val selected = vm.submitTestSession()
            val successor = vm.submitTestSession()
            val last = vm.submitTestSession()
            vm.selectTab(selected)
            assertTrue(vm.deleteSession(preceding.sessionIndex))
            assertSame(selected, vm.navigation.value.selectedSession)
            assertEquals(ApplicationTabSelection.SelectedTab(0), vm.navigation.value.selection)
            assertTrue(vm.deleteSession(selected.sessionIndex))
            assertSame(successor, vm.navigation.value.selectedSession)
            vm.selectTab(last)
            assertTrue(vm.deleteSession(last.sessionIndex))
            assertSame(successor, vm.navigation.value.selectedSession)
        }
    }
    test("background delete and archive preserve New Session selection and unsent text") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val deleted = vm.submitTestSession()
            val archived = vm.submitTestSession()
            vm.selectNewSession()
            vm.newSession.composer.update("keep on plus")
            val input = vm.newSession.composer.state.value
            assertTrue(vm.deleteSession(deleted.sessionIndex))
            assertEquals(ApplicationTabSelection.NewSession, vm.navigation.value.selection)
            assertTrue(vm.closeAndArchiveSession(archived))
            assertTrue(vm.navigation.value.tabs.isEmpty())
            assertEquals(ApplicationTabSelection.NewSession, vm.navigation.value.selection)
            assertEquals(input, vm.newSession.composer.state.value)
        }
    }
}
