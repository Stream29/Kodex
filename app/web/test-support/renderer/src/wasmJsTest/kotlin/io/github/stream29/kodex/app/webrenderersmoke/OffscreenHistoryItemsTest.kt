package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.item.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeHistoryItemRenderer
import io.github.stream29.kodex.cli.history.*
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.utils.applypatch.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

val offscreenHistoryItemsTest by testSuite {
    test("real folded WorkGroup mounts exact children and controlled tool/Patch expansion") { offscreenTest {
        val stored = InMemoryKodexAgentStorage.empty()
        stored.index[1] = StableUserMessage(listOf(ContentItem.InputText("First message")))
        stored.work[2] = StableTextToolEvent("demo", name = "demo", arguments = JsonObject(emptyMap()), result = "Full tool output")
        stored.work[3] = StablePatchToolEvent(
            "patch", diff = Patch("", listOf(AddFileHunk("new.txt", "line"))),
            result = StablePatchToolExecutionResult.Failure("cannot apply"),
        )
        stored.index[4] = StableUserMessage(listOf(ContentItem.InputText("Latest message")))
        for (index in 1..4) stored.timestamp[index] = Instant.fromEpochMilliseconds(index * 1_000L)
        val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
        val model = createAgentHistoryViewModel(
            AgentHistorySource(stored, MutableStateFlow(4), MutableStateFlow(KodexAgentStateValue.UserMessage)),
            owner, MutableStateFlow(false),
        )
        var scene: OffscreenScene? = null
        try {
            withTimeout(5_000) { model.loadState.first { it == AgentHistoryLoadState.Ready } }
            val window = model.historyItems.value
            val items = List(window.size) { window[it] }
            val group = items.filterIsInstance<WorkGroupHistoryItemViewModel>().single()
            val seen = mutableMapOf<Int, HistoryItemViewModel>()
            val rendered = OffscreenScene.create {
                val graphical = rememberComposeHistoryItemRenderer()
                val renderer = object : HistoryItemRenderer by graphical {
                    @Composable override fun Entry(item: HistoryItemViewModel, storageIndex: Int, content: @Composable () -> Unit) {
                        seen[storageIndex] = item
                        graphical.Entry(item, storageIndex, content)
                    }
                }
                renderer.Frame { items.forEach { HistoryItemContent(it, renderer) } }
            }.also { scene = it }
            rendered.settle { rendered.hasDescription("History group: > Take 2 actions") }
            assertFalse(rendered.hasDescription("Expand History item: demo"))
            rendered.clickDescription("History group: > Take 2 actions")
            rendered.settle { rendered.hasDescription("Expand History item: demo") &&
                rendered.hasDescription("Expand History item: Failed to edit new.txt") }
            val children = assertIs<WorkGroupHistoryItemState.Expanded>(group.state.value).children
            children.forEach { assertSame(it, seen[it.index]) }
            val tool = children.filterIsInstance<ToolHistoryItemViewModel>().single()
            val patch = children.filterIsInstance<PatchHistoryItemViewModel>().single()
            rendered.clickDescription("Expand History item: demo")
            rendered.settle { rendered.hasDescription("History tool: demo") && rendered.hasText("> Result") }
            assertIs<ToolHistoryItemState.Expanded>(tool.state.value)
            rendered.click("> Result")
            rendered.settle { rendered.hasText("Result: Full tool output") }
            rendered.clickDescription("History tool: demo")
            rendered.settle { rendered.hasDescription("Expand History item: demo") }
            assertIs<ToolHistoryItemState.Collapsed>(tool.state.value)
            assertFalse(rendered.hasText("Result: Full tool output"))
            rendered.clickDescription("Expand History item: Failed to edit new.txt")
            rendered.settle { rendered.hasText("Error: cannot apply") }
            assertIs<PatchHistoryItemState.Expanded>(patch.state.value)
            rendered.clickDescription("History tool: Failed to edit new.txt")
            rendered.settle { rendered.hasDescription("Expand History item: Failed to edit new.txt") }
            assertIs<PatchHistoryItemState.Collapsed>(patch.state.value)
            rendered.clickDescription("History group: v Take 2 actions")
            rendered.settle { rendered.hasDescription("History group: > Take 2 actions") }
            assertIs<WorkGroupHistoryItemState.Collapsed>(group.state.value)
            assertFalse(rendered.hasDescription("Expand History item: demo"))
            rendered.frame()
            rendered.close()
            scene = null
            assertEquals(AgentHistoryLoadState.Ready, model.loadState.value)
            assertSame(group, model.historyItems.value.peek(items.indexOf(group)))
        } finally {
            scene?.close()
            model.close()
            owner.coroutineContext.job.cancelAndJoin()
        }
    } }

    test("shared item state branches retain blank loading, Error and exact expanding cancel callback") { offscreenTest {
        val job = Job()
        val states = MutableStateFlow<ToolHistoryItemState>(ToolHistoryItemState.Loading(job))
        var expands = 0
        var collapses = 0
        val item = object : ToolHistoryItemViewModel {
            override val index = 42
            override val state = states
            override fun expand() { expands++ }
            override fun collapse() { collapses++ }
        }
        val scene = OffscreenScene.create { HistoryItemContent(item, rememberComposeHistoryItemRenderer()) }
        try {
            scene.settle { scene.hasDescription("History entry 42") }
            assertFalse(scene.hasText("Error"))
            states.value = ToolHistoryItemState.Failed
            scene.settle { scene.hasText("Error") }
            val header = ToolHistoryItemHeader.CommandExecution(
                CommandExecutionHistoryAction.Run("pwd"), CommandExecutionHistoryResult.Failure, 1.milliseconds,
            )
            states.value = ToolHistoryItemState.Collapsed(header)
            scene.settle { scene.hasDescription("Expand History item: Failed to run: pwd") }
            scene.clickDescription("Expand History item: Failed to run: pwd")
            assertEquals(1, expands)
            assertEquals(0, collapses)
            states.value = ToolHistoryItemState.Expanding(header, job)
            scene.settle { scene.hasDescription("Expand History item: Failed to run: pwd") && !scene.hasText("Error") }
            scene.clickDescription("Expand History item: Failed to run: pwd")
            assertEquals(1, expands)
            assertEquals(1, collapses)
            scene.frame()
        } finally {
            scene.close()
            job.cancel()
        }
    } }
}
