package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationDropdowns
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationMenus

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Row
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SidebarContent
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

val sessionViewportTest by testSuite {
    test("short draft viewport traverses real status and opens the mode menu") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val draft = fixture.newSession()
            var settingsClicks = 0
            var directoryClicks = 0
            var dropdowns: RuntimeConfigurationDropdowns? = null
            runNewSessionMosaicTest {
                setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                        val currentDropdowns = RuntimeConfigurationDropdowns.remember(draft)
                        dropdowns = currentDropdowns
                        TuiPopupHost(Modifier.width(24).height(3)) {
                            NewSessionScreen(
                                draft, 24, 3, NewLineKey.ShiftEnter,
                                { width -> newSessionStatusBarRows(width, draft.settings.value) },
                                {},
                                { width ->
                                    NewSessionStatusBar(
                                        width, draft.settings.value, draft.runtimeConfiguration, currentDropdowns,
                                        { directoryClicks++ }, { settingsClicks++ },
                                    )
                                },
                            )
                            RuntimeConfigurationMenus(draft.runtimeConfiguration, currentDropdowns)
                        }
                    }
                }
                withTimeoutOrNull(50) { awaitSnapshot() }
                sendKeyEvent(KeyboardEvent(9))
                sendKeyEvent(KeyboardEvent(13))
                awaitNewSessionSnapshot("First Tab focuses and activates the model trigger") {
                    requireNotNull(dropdowns).model.expanded &&
                        Regex("""\[test-model\s+>]""").containsMatchIn(it)
                }
                sendKeyEvent(KeyboardEvent(27))
                awaitNewSessionSnapshot("Model menu dismissed before the next Tab") {
                    !requireNotNull(dropdowns).model.expanded &&
                        !Regex("""\[test-model\s+>]""").containsMatchIn(it)
                }
                sendKeyEvent(KeyboardEvent(9))
                val focusedMode = awaitNewSessionSnapshot(
                    "Second Tab reveals Questions without skipping to Settings",
                    diagnostics = { "settingsClicks=$settingsClicks directoryClicks=$directoryClicks" },
                ) { "ask user" in it || "no question" in it }
                assertTrue("ask user" in focusedMode || "no question" in focusedMode, focusedMode)
                sendKeyEvent(KeyboardEvent(13))
                val menu = awaitNewSessionSnapshot("Second Tab activates the mode menu") {
                    requireNotNull(dropdowns).requestUserInputMode.expanded &&
                        "ask user" in it && "no question" in it
                }
                assertTrue(requireNotNull(dropdowns).requestUserInputMode.expanded, menu)
                assertTrue("ask user" in menu || "no question" in menu, menu)
                sendKeyEvent(KeyboardEvent(27))
                awaitNewSessionSnapshot("Mode menu dismissed") {
                    !requireNotNull(dropdowns).requestUserInputMode.expanded && "no question" !in it
                }
                assertTrue(!requireNotNull(dropdowns).requestUserInputMode.expanded)
                sendKeyEvent(KeyboardEvent(13))
                awaitNewSessionSnapshot("Escape restored the exact mode trigger") {
                    requireNotNull(dropdowns).requestUserInputMode.expanded &&
                        "ask user" in it && "no question" in it
                }
                sendKeyEvent(KeyboardEvent(27))
                awaitNewSessionSnapshot("Reopened mode menu dismissed") {
                    !requireNotNull(dropdowns).requestUserInputMode.expanded && "no question" !in it
                }
                sendKeyEvent(KeyboardEvent(9))
                sendKeyEvent(KeyboardEvent(13))
                awaitNewSessionSnapshot("Next Tab activates cwd exactly once") { directoryClicks == 1 }
                assertEquals(1, directoryClicks)
                sendKeyEvent(KeyboardEvent(9))
                sendKeyEvent(KeyboardEvent(13))
                awaitNewSessionSnapshot("Next Tab activates Settings exactly once") { settingsClicks == 1 }
                assertEquals(1, settingsClicks)
                assertEquals(1, directoryClicks)
            }
        } finally {
            fixture.close()
        }
    }

    test("draft status controls also remain clipped when sidebars leave a tiny viewport") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val draft = fixture.newSession()
            var columns by mutableStateOf(32)
            runMosaicTest {
                setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    TuiPopupHost(Modifier.width(columns).height(17)) {
                        NewSessionScreen(
                            viewModel = draft,
                            columns = columns,
                            rows = 17,
                            newLineKey = NewLineKey.ShiftEnter,
                            statusBarRows = { width -> newSessionStatusBarRows(width, draft.settings.value) },
                            onSubmit = {},
                            statusBar = { width ->
                                NewSessionStatusBar(
                                    width, draft.settings.value, draft.runtimeConfiguration,
                                    RuntimeConfigurationDropdowns.remember(draft), {}, {},
                                )
                            },
                        )
                    }
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
            val agent = requireNotNull(fixture.persistedSession("Narrow").rootAgent.value)
            var columns by mutableStateOf(110)
            var sidebarColumns by mutableStateOf(24)
            runMosaicTest {
                setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
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
                                suggestionDropdowns = io.github.stream29.kodex.cli.agent.SuggestSubagentTaskDropdowns.remember("suggestions", null),
                                onOpenHistoryEntryContextMenu = { _, _, _, _, _ -> },
                                onBrowseWorkingDirectory = {},
                                onBrowseSuggestedWorkingDirectory = {},
                                onOpenSettings = {},
                            )
                        }
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
