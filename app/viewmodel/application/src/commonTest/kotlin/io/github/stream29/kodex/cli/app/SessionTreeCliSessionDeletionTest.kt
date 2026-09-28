package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import kotlin.test.*

val sessionTreeCliSessionDeletionTest by testSuite {
    test("deleting through the catalog removes data tab and target popup exactly once") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val session = vm.materializeNewSession(0)
            val popup = vm.openDeleteSessionPopup(session.sessionIndex)
            assertTrue(popup.viewModel.delete())
            assertEquals(ApplicationPopupState.Closed, vm.popup.value)
            assertTrue(vm.navigation.value.tabs.all { it is NewSessionViewModel })
            val catalog = vm.openSessionCatalogPopup().viewModel
            catalog.refresh()
            assertTrue(assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.isEmpty())
            assertFalse(vm.deleteSession(session.sessionIndex))
        }
    }
}
