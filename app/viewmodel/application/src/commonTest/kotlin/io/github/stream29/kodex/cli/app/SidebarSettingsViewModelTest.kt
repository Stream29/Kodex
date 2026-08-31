package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.settings.InMemoryKodexGlobalSettings
import io.github.stream29.kodex.cli.settings.KodexGlobalSettings
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.cli.settings.SidebarSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlin.test.assertEquals

val sidebarSettingsViewModelTest by testSuite {
    test("updates each sidebar independently without replacing other settings") {
        val store = InMemoryKodexGlobalSettings(
            KodexGlobalSettings(
                codexHome = Path("/codex"),
                newLineKey = NewLineKey.Enter,
                sidebars = SidebarSettings(
                    leftWidth = 24,
                    rightWidth = 32,
                ),
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val viewModel = SidebarSettingsViewModelImpl(store, scope)

            viewModel.selectRight(SidebarContent.TerminalSessions)
            val rightUpdated = viewModel.state.first {
                it.right == SidebarContent.TerminalSessions
            }
            assertEquals(
                SidebarSettings(
                    left = SidebarContent.TerminalSessions,
                    right = SidebarContent.TerminalSessions,
                    leftWidth = 24,
                    rightWidth = 32,
                ),
                rightUpdated,
            )

            viewModel.selectLeft(SidebarContent.None)
            assertEquals(
                SidebarSettings(
                    left = SidebarContent.None,
                    right = SidebarContent.TerminalSessions,
                    leftWidth = 24,
                    rightWidth = 32,
                ),
                viewModel.state.first { it.left == SidebarContent.None },
            )

            viewModel.resizeLeft(30)
            viewModel.resizeRight(18)
            assertEquals(
                SidebarSettings(
                    left = SidebarContent.None,
                    right = SidebarContent.TerminalSessions,
                    leftWidth = 30,
                    rightWidth = 18,
                ),
                viewModel.state.first { it.leftWidth == 30 && it.rightWidth == 18 },
            )
            assertEquals(NewLineKey.Enter, store.settings.value.newLineKey)
        } finally {
            scope.cancel()
        }
    }
}
