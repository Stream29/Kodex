@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.cli.historyindex.HistoryIndexInteractionRequest
import io.github.stream29.kodex.cli.historyindex.HistoryIndexMenuRequest
import io.github.stream29.kodex.cli.historyindex.HistoryIndexHoverPopup
import io.github.stream29.kodex.cli.historyindex.HistoryIndexContextMenu
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationDropdowns

import io.github.stream29.kodex.app.test.seedTestHistory

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import de.infix.testBalloon.framework.core.testSuite
import de.infix.testBalloon.framework.core.TestCompartment
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.settings.SidebarContent
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// These clips load real repository/RPC payloads on Dispatchers.Default. Virtual
// frame time must not outrun those reads and pointer-anchor placement. Frontend
// observation, interactions, drawing and layout share one serialized dispatcher.
private val historyRecordingDispatcher = Dispatchers.Default.limitedParallelism(1)

val docsHistoryRecordingTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("history index hover check out and return to latest use real history") {
        withContext(historyRecordingDispatcher) {
        // English translation of a contiguous, user-authorized local feature
        // discussion. Preserve real event types, index gaps and answered choices.
        val history = Json.decodeFromString<Map<Int, StableIndexEvent>>(
            requireNotNull(Thread.currentThread().contextClassLoader.getResource("docs/history-index.en.json")).readText(),
        )
        assertEquals(13, history.size)
        assertEquals(4, history.values.map { it::class }.toSet().size)
        var sourceIndex = -1
        val fixture = SessionViewModelTestFixture.create(this, frontendDispatcher = historyRecordingDispatcher) { home ->
            sourceIndex = seedTestHistory(home, "History Index design", history.filterKeys { it < 826 })
        }
        val session = fixture.rpc.sessions.open(sourceIndex)
        val binding = fixture.rpc.views.open(sourceIndex).current()
        val agent = requireNotNull(session.rootAgent.value)
        try {
            var hover by mutableStateOf<HistoryIndexInteractionRequest?>(null)
            var menu by mutableStateOf<HistoryIndexMenuRequest?>(null)
            val clip = DocsClip("history-index", width = 100, height = 28)
            runMosaicTest(MosaicSnapshots) {
                setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(28)) {
                        Row {
                            SessionSidebar(
                                SessionSidebarSide.Left, SidebarContent.HistoryIndex,
                                agent, rememberTuiDropdownState(), 36, 28,
                                {}, {}, {},
                                // Keep the fixture request across the popup's input-surface switch.
                                // The complete shell's delayed hover-dismiss lifecycle is separate.
                                onHistoryIndexHoverChanged = { request, visible -> if (visible && menu == null) hover = request },
                                onOpenHistoryIndexMenu = { menu = it; hover = null },
                            )
                            AgentRuntimeScreen(
                                agent, 64, 28, io.github.stream29.kodex.cli.settings.NewLineKey.ShiftEnter,
                                RuntimeConfigurationDropdowns.remember(agent),
                                io.github.stream29.kodex.cli.agent.SuggestSubagentTaskDropdowns.remember(
                                    agent.suggestSubagentTask, null,
                                ),
                                { _, _, _, _, _ -> }, {}, {}, {},
                            )
                        }
                        HistoryIndexHoverPopup(hover, 64, 28, {})
                        HistoryIndexContextMenu(menu) {
                            menu = null
                        }
                    }
                }
                clip.add(settle(), "History Index")
                repeat(5) { settle() }
                suspend fun preview(rowText: String, title: String): Int {
                    hover = null
                    sendMouseEvent(MouseEvent(95, 26, MouseEvent.Type.Motion))
                    val rows = settle().draw().render(com.jakewharton.mosaic.terminal.AnsiLevel.NONE, false).lines()
                    val row = rows.indexOfFirst { rowText in it.take(36) }
                    assertTrue(row >= 0, "Missing sidebar row: $rowText\n${rows.joinToString("\n")}")
                    sendMouseEvent(MouseEvent(6, row, MouseEvent.Type.Motion))
                    settle()
                    assertTrue(requireNotNull(hover).anchor.isPlaced)
                    delay(500)
                    repeat(3) { settle() }
                    clip.add(settle(), title)
                    return row
                }
                preview("Next, let's improve", "User message")
                preview("Read kanban", "Plan update")
                preview("Created the discussion", "Assistant Final")
                val questionRow = preview("Which parts of History", "Node and content")
                assertEquals(783, requireNotNull(hover).index)
                sendMouseEvent(MouseEvent(6, questionRow, MouseEvent.Type.Press, MouseEvent.Button.Right))
                settle()
                sendMouseEvent(MouseEvent(6, questionRow, MouseEvent.Type.Release, MouseEvent.Button.Right))
                clip.add(settle(), "Check out")
                clickLabel("[Check out")
                // Loading an older, variable-height entry crosses the real
                // asynchronous history window before its scroll can settle.
                repeat(20) {
                    delay(25)
                    settle()
                }
                clip.add(settle(), "[↓]")
                assertNull(menu)
                assertTrue(!agent.history.followsLatest)
                assertEquals(824, fixture.rpc.services.index.getLatestIndex(sourceIndex), "Check out must not alter storage")
                binding.appendUserMessage((history.getValue(826) as StableUserMessage).content)
                clip.add(settle(), "[↓]")
                assertTrue(!agent.history.followsLatest)
                clickLabel("[↓]")
                hover = null
                sendMouseEvent(MouseEvent(98, 26, MouseEvent.Type.Motion))
                repeat(3) { settle() }
                clip.add(settle(), "Next, let's review the plan")
                assertTrue(agent.history.followsLatest)
            }
            clip.save()
        } finally {
            fixture.close()
        }
        }
    }

    test("real revert dialog cancels and reverts disposable history") {
        withContext(historyRecordingDispatcher) {
        var sourceIndex = -1
        val fixture = SessionViewModelTestFixture.create(this, frontendDispatcher = historyRecordingDispatcher) { home ->
            sourceIndex = seedTestHistory(home, "History example", (2..5).associateWith {
                StableUserMessage(listOf(ContentItem.InputText("Offline example entry $it")))
            })
        }
        val store = fixture.rpc.sessions
        val session = store.open(sourceIndex)
        val binding = fixture.rpc.views.open(sourceIndex).current()
        val agent = requireNotNull(session.rootAgent.value)
        try {
            withTimeout(5.seconds) {
                while (!agent.history.contains(agent.history.historyItems.value.generation, 4)) {
                    agent.history.historyItems.value.requestOlder()
                    delay(20)
                }
            }
            val clip = DocsClip("history-actions")
            var selected by mutableStateOf<PersistedSessionViewModel>(session)
            var tabs by mutableStateOf(listOf(session))
            var menu by mutableStateOf<Triple<Int, TuiPopupAnchor, IntOffset?>?>(null)
            var menuGeneration by mutableStateOf(0L)
            runMosaicTest(MosaicSnapshots) {
                setContentAndSnapshot {
                    val scope = rememberCoroutineScope()
                    TuiPopupHost(Modifier.width(80).height(18)) {
                        Column(Modifier.width(80).height(18)) {
                            SessionTabBar(
                                collectSessionTabRenderStates(tabs, tabs.indexOf(selected)),
                                mutableStateOf(""), 80,
                                { selected = it as PersistedSessionViewModel },
                                { _, _, _, _ -> }, {}, {},
                            )
                            AgentRuntimeScreen(
                                requireNotNull(selected.rootAgent.value), 80, 17, io.github.stream29.kodex.cli.settings.NewLineKey.ShiftEnter,
                                RuntimeConfigurationDropdowns.remember(requireNotNull(selected.rootAgent.value)),
                                io.github.stream29.kodex.cli.agent.SuggestSubagentTaskDropdowns.remember(
                                    requireNotNull(selected.rootAgent.value).suggestSubagentTask, null,
                                ),
                                { generation, index, _, anchor, position ->
                                    menuGeneration = generation
                                    menu = Triple(index, anchor, position)
                                }, {}, {}, {},
                            )
                        }
                        menu?.let { (target, anchor, position) ->
                            HistoryEntryContextMenuPopup(
                                anchor, position, { menu = null },
                                {
                                    requireNotNull(selected.rootAgent.value).requestHistoryRevert(target + 1, menuGeneration)
                                    menu = null
                                },
                                {
                                    menu = null
                                    scope.launch {
                                        val fork = store.open(
                                            selected.fork(requireNotNull(selected.rootAgent.value), target + 1, menuGeneration),
                                        )
                                        tabs = tabs + fork
                                        selected = fork
                                    }
                                },
                            )
                        }
                        AgentHistoryRevertDialog(selected.rootAgent.value)
                    }
                }
                clip.add(settle(), "Offline example entry")
                clickLabel("Offline example entry 4", MouseEvent.Button.Right)
                clip.add(settle(), "Fork from here")
                clickLabel("Fork from here")
                withTimeout(5.seconds) { while (tabs.size < 2) delay(20) }
                clip.add(settle(), "[fork]")
                assertEquals(5, fixture.rpc.services.index.getLatestIndex(sourceIndex), "Fork must preserve its source")
                assertEquals(4, fixture.rpc.services.index.getLatestIndex(selected.sessionIndex))
                clickLabel("[History example]")
                clip.add(settle(), "Offline example entry 5")
                clickLabel("Offline example entry 4", MouseEvent.Button.Right)
                clip.add(settle(), "Revert to here")
                clickLabel("Revert to here")
                clip.add(settle(), "This cannot be undone.")
                clickLabel("Cancel")
                assertIs<AgentHistoryActionState.None>(agent.historyAction.value)
                assertEquals(5, fixture.rpc.services.index.getLatestIndex(sourceIndex))
                clip.add(settle(), "Offline example entry 5")
                clickLabel("Offline example entry 4", MouseEvent.Button.Right)
                clip.add(settle(), "Revert to here")
                clickLabel("Revert to here")
                clip.add(settle(), "Keep the selected history entry")
                clickLabel("[Revert]")
                withTimeout(5.seconds) { binding.latestIndex.first { it == 4 } }
                var rendered = settle()
                withTimeout(5.seconds) {
                    while (!rendered.draw().render(AnsiLevel.NONE, false).contains("Offline example entry 4")) {
                        delay(10)
                        rendered = settle()
                    }
                }
                clip.add(rendered, "Offline example entry 4")
                assertEquals(4, fixture.rpc.services.index.getLatestIndex(sourceIndex))
            }
            clip.save()
        } finally {
            fixture.close()
        }
        }
    }
}
