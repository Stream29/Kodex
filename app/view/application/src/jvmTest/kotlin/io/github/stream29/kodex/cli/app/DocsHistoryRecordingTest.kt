package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState
import io.github.stream29.kodex.app.agent.contract.AgentHistoryTarget
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val docsHistoryRecordingTest by testSuite {
    test("history index hover check out and return to latest use real history") {
        val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
        val store = testSessionViewModelRegistry(repository, this)
        val session = store.create {
                KodexAgentSettings(model = OpenAiModelId("test-model"), threadName = "History Index design")
        }
        val root = repository.open(session.sessionIndex)
        // English translation of a contiguous, user-authorized local feature
        // discussion. Preserve real event types, index gaps and answered choices.
        val history = Json.decodeFromString<Map<Int, StableIndexEvent>>(
            requireNotNull(Thread.currentThread().contextClassLoader.getResource("docs/history-index.en.json")).readText(),
        )
        assertEquals(13, history.size)
        assertEquals(4, history.values.map { it::class }.toSet().size)
        root.runtime.modify { storage ->
            history.filterKeys { it < 826 }.forEach { (index, entry) -> storage.index[index] = entry }
        }
        val agent = session.rootAgent
        try {
            var hover by mutableStateOf<HistoryIndexInteractionRequest?>(null)
            var menu by mutableStateOf<HistoryIndexMenuRequest?>(null)
            var checkedOut: Int? = null
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
                                RuntimeConfigurationDropdowns.remember("suggestions"),
                                { _, _, _, _ -> }, {}, {}, {},
                            )
                        }
                        HistoryIndexHoverPopup(hover, 64, 28, {})
                        HistoryIndexContextMenu(menu, agent, { menu = null }) {
                            checkedOut = it.index
                            agent.history.requestScrollToStorageIndex(it.index)
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
                    withContext(Dispatchers.Default) { delay(500) }
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
                    withContext(Dispatchers.Default) { delay(25) }
                    settle()
                }
                clip.add(settle(), "[↓]")
                assertEquals(783, checkedOut)
                assertEquals(824, root.storage.latestIndex(), "Check out must not alter storage")
                root.runtime.modify { storage ->
                    storage.index[826] = history.getValue(826)
                }
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
            store.shutdown()
            repository.cancelAndJoin()
        }
    }

    test("real revert dialog cancels and reverts disposable history") {
        val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
        val store = testSessionViewModelRegistry(repository, this)
        val session = store.create {
            KodexAgentSettings(model = OpenAiModelId("test-model"), threadName = "History example")
        }
        val root = repository.open(session.sessionIndex)
        root.runtime.modify { storage ->
            for (i in 2..5) storage.index[i] =
                StableUserMessage(listOf(ContentItem.InputText("Offline example entry $i")))
        }
        val agent = session.rootAgent
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    while (!agent.history.contains(agent.history.historyItems.value.generation, 4)) {
                        agent.history.historyItems.value.requestOlder()
                        delay(20)
                    }
                }
            }
            val clip = DocsClip("history-actions")
            var selected by mutableStateOf<PersistedSessionViewModel>(session)
            var tabs by mutableStateOf(listOf(session))
            var menu by mutableStateOf<Triple<AgentHistoryTarget, TuiPopupAnchor, IntOffset?>?>(null)
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
                                selected.rootAgent, 80, 17, io.github.stream29.kodex.cli.settings.NewLineKey.ShiftEnter,
                                RuntimeConfigurationDropdowns.remember(selected.rootAgent),
                                RuntimeConfigurationDropdowns.remember("suggestions"),
                                { target, _, anchor, position -> menu = Triple(target, anchor, position) }, {}, {}, {},
                            )
                        }
                        menu?.let { (target, anchor, position) ->
                            HistoryEntryContextMenuPopup(
                                anchor, position, { menu = null },
                                { selected.rootAgent.requestHistoryRevert(target); menu = null },
                                {
                                    menu = null
                                    scope.launch {
                                        val fork = store.open(selected.fork(selected.rootAgent, target))
                                        tabs = tabs + fork
                                        selected = fork
                                    }
                                },
                            )
                        }
                        AgentHistoryRevertDialog(selected.rootAgent)
                    }
                }
                clip.add(settle(), "Offline example entry")
                clickLabel("Offline example entry 4", MouseEvent.Button.Right)
                clip.add(settle(), "Fork from here")
                clickLabel("Fork from here")
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) { while (tabs.size < 2) delay(20) }
                }
                clip.add(settle(), "[fork]")
                assertEquals(5, root.storage.latestIndex(), "Fork must preserve its source")
                assertEquals(4, repository.open(selected.sessionIndex).storage.index.floorToIndex(Int.MAX_VALUE))
                clickLabel("[History example]")
                clip.add(settle(), "Offline example entry 5")
                clickLabel("Offline example entry 4", MouseEvent.Button.Right)
                clip.add(settle(), "Revert to here")
                clickLabel("Revert to here")
                clip.add(settle(), "This cannot be undone.")
                clickLabel("Cancel")
                assertIs<AgentHistoryActionState.None>(agent.historyAction.value)
                assertEquals(5, root.storage.latestIndex())
                clip.add(settle(), "Offline example entry 5")
                clickLabel("Offline example entry 4", MouseEvent.Button.Right)
                clip.add(settle(), "Revert to here")
                clickLabel("Revert to here")
                clip.add(settle(), "Keep the selected history entry")
                clickLabel("[Revert]")
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) { root.runtime.latestIndex.first { it == 4 } }
                }
                clip.add(settle(), "Offline example entry 4")
                assertEquals(4, root.storage.latestIndex())
            }
            clip.save()
        } finally {
            store.shutdown()
            repository.cancelAndJoin()
        }
    }
}
