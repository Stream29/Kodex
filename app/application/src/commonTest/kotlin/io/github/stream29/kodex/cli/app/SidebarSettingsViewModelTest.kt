package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.rpc.server.defaultBackendSettings
import kotlinx.coroutines.flow.first
import kotlin.test.*

val sidebarSettingsViewModelTest by testSuite {
    test("contents persist through the backend while widths remain invocation-local") {
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
            val persisted = UnifiedSettingsStore.open(home, defaultBackendSettings()).frontendSettings.value
            assertEquals(SidebarContent.None, persisted.sidebars.left)
            assertEquals(SidebarContent.TerminalSessions, persisted.sidebars.right)
        }
    }
}
