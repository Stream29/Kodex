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
                ),
                rightUpdated,
            )

            viewModel.selectLeft(SidebarContent.None)
            assertEquals(
                SidebarSettings(
                    left = SidebarContent.None,
                    right = SidebarContent.TerminalSessions,
                ),
                viewModel.state.first { it.left == SidebarContent.None },
            )
            assertEquals(NewLineKey.Enter, store.settings.value.newLineKey)
        } finally {
            scope.cancel()
        }
    }
}
