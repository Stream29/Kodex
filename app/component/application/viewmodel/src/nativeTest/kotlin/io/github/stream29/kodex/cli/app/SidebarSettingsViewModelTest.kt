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
            vm.toggle(ToolWindowId.HistoryIndex)
            vm.toggle(ToolWindowId.TerminalSessions)
            vm.toggle(ToolWindowId.Sessions)
            vm.collapse(ToolWindowSide.Left)
            vm.resizeLeft(19)
            vm.resizeRight(17)
            val result = vm.state.first {
                it.activeLeft == null && it.activeRight == ToolWindowId.TerminalSessions &&
                    it.leftWidth == 19 && it.rightWidth == 17
            }
            vm.initializeViewport(200) // Later resizes must not reset the user's widths.
            assertEquals(result, vm.state.value)
            val persisted = UnifiedSettingsStore.open(home, defaultBackendSettings()).frontendSettings.value
            assertNull(persisted.sidebars.activeLeft)
            assertEquals(ToolWindowId.TerminalSessions, persisted.sidebars.activeRight)
        }
    }
}
