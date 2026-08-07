package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.cli.session.RootSessionViewModel
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val fixedSidebarRunningIndicatorFrame = mutableStateOf("⠋")

class SessionAgentSidebarTest {
    @Test
    fun emptySidebarOmitsTheAgentTreeSection() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                SessionAgentSidebar(
                    tree = null,
                    expanded = true,
                    columns = 28,
                    rows = 8,
                    runningIndicatorFrame = fixedSidebarRunningIndicatorFrame,
                    onHoverChanged = {},
                    onToggleExpanded = {},
                    onSelectAgent = {},
                    onOpenShellSessionMenu = {},
                )
            }

            assertTrue("←" in snapshot)
            assertFalse("Agent tree" in snapshot)
            assertFalse("No agents" in snapshot)
        }
    }

    @Test
    fun runningAgentTreeLabelPrefixesTheSpinnerWithoutSpacing() {
        assertEquals(
            "⠋worker",
            agentTreeNodeDisplayLabel(
                nodeLabel = "worker",
                running = true,
                runningIndicatorFrame = "⠋",
                maximumColumns = 20,
            ),
        )
        assertEquals(
            "worker",
            agentTreeNodeDisplayLabel(
                nodeLabel = "worker",
                running = false,
                runningIndicatorFrame = "⠋",
                maximumColumns = 20,
            ),
        )
        assertEquals(
            "⠋abcdefghijklmnop...",
            agentTreeNodeDisplayLabel(
                nodeLabel = "abcdefghijklmnopqrstuv",
                running = true,
                runningIndicatorFrame = "⠋",
                maximumColumns = 20,
            ),
        )
    }

    @Test
    fun shellSessionRowsWrapTheirCommandWithoutFlatteningHardLines() {
        assertEquals(
            listOf(
                "42: abcd",
                "efghijkl",
                "next",
            ),
            shellSessionSidebarLines(
                sessionId = 42,
                command = "abcdefghijkl\nnext",
                columns = 8,
            ),
        )
    }

    @Test
    fun shellSessionContextMenusUseTheClickPositionAndStayInsideTheHost() {
        val surfaceSize = IntSize(width = 30, height = 10)
        val popupContentSize = IntSize(width = 8, height = 2)

        assertEquals(
            IntOffset(x = 9, y = 4),
            shellSessionContextMenuPosition(
                anchorPosition = IntOffset(x = 4, y = 3),
                clickPosition = IntOffset(x = 5, y = 1),
                surfaceSize = surfaceSize,
                popupContentSize = popupContentSize,
            ),
        )
        assertEquals(
            IntOffset(x = 22, y = 8),
            shellSessionContextMenuPosition(
                anchorPosition = IntOffset(x = 25, y = 9),
                clickPosition = IntOffset(x = 2, y = 1),
                surfaceSize = surfaceSize,
                popupContentSize = popupContentSize,
            ),
        )
        assertEquals(
            IntOffset(x = 4, y = 3),
            shellSessionContextMenuPosition(
                anchorPosition = IntOffset(x = 4, y = 3),
                clickPosition = null,
                surfaceSize = surfaceSize,
                popupContentSize = popupContentSize,
            ),
        )
    }

    @Test
    fun shellSessionRowsOpenTheirMenuOnSecondaryClick() = runTest {
        var menuOpened by mutableStateOf(false)
        var menuClickPosition: IntOffset? = null

        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(modifier = Modifier.width(8)) {
                    ShellSessionSidebarRow(
                        lines = listOf("42: abcd", "efghijkl"),
                        onOpenMenu = { _, clickPosition ->
                            menuClickPosition = clickPosition
                            menuOpened = true
                        },
                    )
                    Text(menuOpened.toString())
                }
            }
            assertEquals("42: abcd\nefghijkl\nfalse", snapshot)

            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Press, MouseEvent.Button.Right))
            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Release))
            awaitSnapshot()

            assertEquals(true, menuOpened)
            assertEquals(IntOffset(x = 1, y = 0), menuClickPosition)
        }
    }

    @Test
    fun nestedAgentsAreLazilyExpandedAndUseTheirLastPathSegment() = runTest {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val root = repository.open(repository.create())
            val worker = root.subagents.open(root.subagents.create())
            val reviewer = worker.subagents.open(worker.subagents.create())
            root.initialize("Root / title")
            worker.initialize("/root/worker")
            reviewer.initialize("/root/worker/reviewer")
            val viewModel = RootSessionViewModel(root)

            try {
                viewModel.refresh()
                val tree = withTimeout(5.seconds) {
                    viewModel.state.first { state ->
                        state.agents.size == 3 && state.agents.all { entry ->
                            entry.viewModel.state.value.durable.settings != null
                        }
                    }
                }
                val rootAgent = requireNotNull(tree.agents.firstOrNull { entry ->
                    entry.agentId == tree.rootAgentId
                })
                val workerAgent = requireNotNull(tree.agents.firstOrNull { entry ->
                    entry.parentAgentId == rootAgent.agentId
                })
                val reviewerAgent = requireNotNull(tree.agents.firstOrNull { entry ->
                    entry.parentAgentId == workerAgent.agentId
                })

                assertEquals(
                    listOf(rootAgent.agentId, workerAgent.agentId),
                    tree.visibleAgentTreeEntries(setOf(tree.rootAgentId)).map { entry -> entry.agentId },
                )
                assertEquals(
                    listOf(rootAgent.agentId, workerAgent.agentId, reviewerAgent.agentId),
                    tree.visibleAgentTreeEntries(setOf(tree.rootAgentId, workerAgent.agentId))
                        .map { entry -> entry.agentId },
                )
                assertEquals("Root / title", tree.agentTreeNodeLabel(rootAgent, "Root / title"))
                assertEquals("worker", tree.agentTreeNodeLabel(workerAgent, "/root/worker"))
                assertEquals("reviewer", tree.agentTreeNodeLabel(reviewerAgent, "/root/worker/reviewer"))
            } finally {
                viewModel.close()
                repository.cancelAndJoin()
            }
        }
    }
}

private suspend fun KodexAgentSession.initialize(threadName: String) {
    runtime.modify { storage ->
        storage.initialize(
            KodexAgentSettings(
                model = OpenAiModelId("test-model"),
                threadName = threadName,
            ),
        )
    }
}
