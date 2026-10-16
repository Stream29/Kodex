package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem as Fs
import kotlin.test.*

val workingDirectoryComponentOwnershipTest by testSuite {
    test("Agent cwd selection stays bound when a different draft tab is selected") {
        applicationFixture { app, home ->
            val application = app.viewModel
            val session = assertNotNull(application.materializeNewSession(assertIs<NewSessionViewModel>(application.navigation.value.selected)))
            val agent = assertNotNull(session.rootAgent.value)
            val popup = application.openWorkingDirectoryPopup(agent)
            val other = application.createNewSessionTab()
            val otherCwd = other.settings.value.cwd
            val chosen = Path(home, "agent-chosen")
            Fs.createDirectories(chosen)
            popup.viewModel.select(chosen)
            agent.settings.first { it.cwd == chosen }
            assertSame(agent, popup.viewModel.target)
            assertEquals(otherCwd, other.settings.value.cwd)
            assertFalse(popup.viewModel.isActive)
            assertSame(popup, application.popup.value)
            assertTrue(application.dismissPopup(popup))
        }
    }
    test("popup replacement and target closure reject stale cwd commands") {
        applicationFixture { app, _ ->
            val application = app.viewModel
            val target = application.navigation.value.selected
            val old = application.openWorkingDirectoryPopup(target)
            val current = application.openWorkingDirectoryPopup(target)
            assertFalse(old.viewModel.isActive)
            assertTrue(current.viewModel.isActive)
            assertFalse(application.dismissPopup(old))
            assertFailsWith<IllegalStateException> { old.viewModel.select(Path("/late")) }
            assertSame(current, application.popup.value)
            assertTrue(application.closeTab(target))
            assertFalse(current.viewModel.isActive)
            assertEquals(ApplicationPopupState.Closed, application.popup.value)
        }
    }
    test("a nonpending suggestion cannot open a cwd chooser or edit its source Agent") {
        applicationFixture { app, _ ->
            val application = app.viewModel
            val session = assertNotNull(application.materializeNewSession(assertIs<NewSessionViewModel>(application.navigation.value.selected)))
            val agent = assertNotNull(session.rootAgent.value)
            val before = agent.settings.value.cwd
            assertNull(application.openSuggestedWorkingDirectoryPopup(agent, "not-pending"))
            assertEquals(before, agent.settings.value.cwd)
            assertEquals(ApplicationPopupState.Closed, application.popup.value)
        }
    }
}
