package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import kotlinx.coroutines.flow.first
import kotlin.test.*

val sessionActionComponentOwnershipTest by testSuite {
    test("rename remains bound to its captured session after the selected tab changes") {
        applicationFixture { app, _ ->
            val application = app.viewModel
            val target = assertNotNull(application.materializeNewSession(assertIs<NewSessionViewModel>(application.navigation.value.selected)))
            val popup = application.openRenameSessionPopup(target)
            val other = application.createNewSessionTab()
            val otherName = other.name.value
            assertSame(target, popup.viewModel.target)
            popup.viewModel.updateDraftName("  captured rename  ")
            popup.viewModel.rename()
            target.name.first { it == "captured rename" }
            assertEquals(otherName, other.name.value)
            assertSame(popup, application.popup.value)
            assertTrue(application.dismissPopup(popup))
            assertFalse(popup.viewModel.isActive)
        }
    }
    test("replacing a rename closes only the old child and protects the new popup") {
        applicationFixture { app, _ ->
            val application = app.viewModel
            val target = assertNotNull(application.materializeNewSession(assertIs<NewSessionViewModel>(application.navigation.value.selected)))
            val old = application.openRenameSessionPopup(target)
            val current = application.openRenameSessionPopup(target)
            assertFalse(old.viewModel.isActive)
            assertTrue(current.viewModel.isActive)
            assertFalse(application.dismissPopup(old))
            assertSame(current, application.popup.value)
            assertFailsWith<IllegalStateException> { old.viewModel.rename() }
            assertTrue(application.closeTab(target))
            assertEquals(ApplicationPopupState.Closed, application.popup.value)
            assertFalse(current.viewModel.isActive)
        }
    }
    test("missing captured deletion returns false without closing another session") {
        applicationFixture { app, _ ->
            val application = app.viewModel
            val target = assertNotNull(application.materializeNewSession(assertIs<NewSessionViewModel>(application.navigation.value.selected)))
            val popup = application.openDeleteSessionPopup(target.sessionIndex + 1000)
            assertFalse(popup.viewModel.delete())
            assertTrue(application.navigation.value.tabs.any { it === target })
            assertSame(popup, application.popup.value)
            assertTrue(popup.viewModel.isActive)
            assertTrue(application.dismissPopup(popup))
        }
    }
}
