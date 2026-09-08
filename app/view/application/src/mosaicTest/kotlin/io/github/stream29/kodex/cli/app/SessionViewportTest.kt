package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Row
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SidebarContent
import kotlin.test.assertTrue

val sessionViewportTest by testSuite {
    test("draft status controls also remain clipped when sidebars leave a tiny viewport") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val draft = fixture.newSession("Narrow draft")
            var columns by mutableStateOf(32)
            runMosaicTest {
                setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(columns).height(17)) {
                        NewSessionScreen(
                            viewModel = draft,
                            columns = columns,
                            rows = 17,
                            newLineKey = NewLineKey.ShiftEnter,
                            dropdowns = RuntimeConfigurationDropdowns.remember(draft),
                            onSubmit = {},
                            onBrowseWorkingDirectory = {},
                            onOpenSettings = {},
                        )
                    }
                }
                for (width in listOf(8, 1, 16, 32)) {
                    columns = width
                    val snapshot = awaitSnapshot()
                    assertTrue(snapshot.lines().all { it.length <= width }, snapshot)
                }
            }
        } finally {
            fixture.close()
        }
    }

    test("sidebar width changes keep runtime drawing inside the terminal without any context menu") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val agent = fixture.persistedSession("Narrow").rootAgent
            var columns by mutableStateOf(110)
            var sidebarColumns by mutableStateOf(24)
            runMosaicTest {
                setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(columns).height(17)) {
                        Row(Modifier.width(columns).height(17)) {
                            if (sidebarColumns > 0) {
                                SessionSidebar(
                                    side = SessionSidebarSide.Left,
                                    content = SidebarContent.HistoryIndex,
                                    selectedAgent = agent,
                                    dropdownState = rememberTuiDropdownState(),
                                    columns = sidebarColumns,
                                    rows = 17,
                                    onHoverChanged = {},
                                    onToggleExpanded = {},
                                    onOpenShellSessionMenu = {},
                                )
                            }
                            AgentRuntimeScreen(
                                viewModel = agent,
                                columns = columns - sidebarColumns,
                                rows = 17,
                                newLineKey = NewLineKey.ShiftEnter,
                                dropdowns = RuntimeConfigurationDropdowns.remember(agent),
                                suggestionDropdowns = RuntimeConfigurationDropdowns.remember("suggestions"),
                                onOpenHistoryEntryContextMenu = { _, _, _, _, _ -> },
                                onBrowseWorkingDirectory = {},
                                onBrowseSuggestedWorkingDirectory = {},
                                onOpenSettings = {},
                            )
                        }
                    }
                }
                for ((width, sidebar) in listOf(32 to 24, 32 to 12, 32 to 31, 32 to 0, 110 to 24)) {
                    columns = width
                    sidebarColumns = sidebar
                    val snapshot = awaitSnapshot()
                    assertTrue(snapshot.lines().all { it.length <= width }, snapshot)
                }
            }
        } finally {
            fixture.close()
        }
    }
}
