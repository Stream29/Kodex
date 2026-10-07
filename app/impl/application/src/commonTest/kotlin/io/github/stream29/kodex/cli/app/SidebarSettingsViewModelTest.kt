package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.settings.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

val sidebarSettingsViewModelTest by testSuite {
    test("contents persist locally while initial and resized widths remain invocation-local") {
        applicationFixture { app, home ->
            val vm = app.sidebarSettings
            assertEquals(30, vm.state.value.leftWidth)
            vm.selectRight(SidebarContent.TerminalSessions)
            vm.selectLeft(SidebarContent.None)
            vm.resizeLeft(19)
            vm.resizeRight(17)
            val result = vm.state.first {
                it.left == SidebarContent.None && it.right == SidebarContent.TerminalSessions &&
                    it.leftWidth == 19 && it.rightWidth == 17
            }
            vm.initializeViewport(200) // Later resizes must not reset the user's widths.
            assertEquals(result, vm.state.value)
            val persisted = openCliFrontendSettings(home).settings.value
            assertEquals(SidebarContent.None, persisted.sidebars.left)
            assertEquals(SidebarContent.TerminalSessions, persisted.sidebars.right)
        }
    }
}
