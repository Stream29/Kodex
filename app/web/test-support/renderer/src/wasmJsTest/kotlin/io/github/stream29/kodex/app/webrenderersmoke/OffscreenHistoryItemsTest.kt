package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
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
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration
import kotlin.time.Instant

val offscreenHistoryItemsTest by testSuite {
    for (dark in listOf(false, true)) {
        for (status in listOf("failed", "running", "streaming", "starting", "in_progress", "inprogress", "completed")) {
            test("lazy collapsed tool uses status color for title and elapsed dark=$dark status=$status") { offscreenTest {
                val scheme = if (dark) darkColorScheme() else lightColorScheme()
                val scene = OffscreenScene.create(width = 360) {
                    MaterialTheme(colorScheme = scheme) {
                        rememberComposeHistoryItemRenderer().Collapsed(
                            "Lazy tool", status, 123.milliseconds, HistoryDisclosureState.Collapsed,
                        ) {}
                    }
                }
                try {
                    scene.settle { scene.hasText("Lazy tool") }
                    val expected = when (status) {
                        "failed" -> scheme.error
                        "completed" -> scheme.onSurface
                        else -> scheme.tertiary
                    }
                    for (text in listOf("Lazy tool", 123.milliseconds.historyElapsedSuffix())) {
                        val layouts = mutableListOf<TextLayoutResult>()
                        assertTrue(assertNotNull(scene.text(text).config.getOrNull(
                            SemanticsActions.GetTextLayoutResult,
                        )?.action)(layouts))
                        assertEquals(expected, layouts.single().layoutInput.style.color, text)
                    }
                    assertEquals(1, scene.nodes().count {
                        it.config.getOrNull(SemanticsProperties.TestTag) == "history-tool-disclosure-Collapsed"
                    })
                } finally { scene.close() }
            } }
        }
    }
    for (width in listOf(180, 360)) for (dark in listOf(false, true)) {
        test("running patch title keeps status and typography on expansion width=$width dark=$dark") { offscreenTest {
            val diff = Patch("", listOf(AddFileHunk("folder/进行中.txt", "line")))
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            val scene = OffscreenScene.create(width = width) {
                MaterialTheme(colorScheme = scheme) {
                    val renderer = rememberComposeHistoryItemRenderer()
                    renderer.Text("Patch body reference", HistoryTextStyle.Normal)
                    HistoryEventContent(PendingPatchToolEvent("pending-patch", diff = diff), renderer)
                }
            }
            try {
                val title = "Editing 进行中.txt"
                scene.settle { scene.hasDescription("History tool: $title") }
                fun layout(value: String): TextLayoutResult {
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(assertNotNull(scene.text(value).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                    return layouts.single()
                }
                val before = layout(title).layoutInput.style
                assertTrue(before.fontSize.value >= layout("Patch body reference").layoutInput.style.fontSize.value)
                assertTrue((before.fontWeight?.weight ?: 0) >= FontWeight.SemiBold.weight)
                assertEquals(scheme.tertiary, before.color)
                scene.clickDescription("History tool: $title")
                scene.settle { scene.hasText("+ line") }
                val after = layout(title).layoutInput.style
                assertEquals(before.fontSize, after.fontSize)
                assertEquals(before.fontWeight, after.fontWeight)
                assertEquals(before.color, after.color)
            } finally { scene.close() }
        } }
    }
    for (width in listOf(180, 360)) for (scale in listOf(1f, 1.5f))
        for (dark in listOf(false, true)) for (failed in listOf(false, true)) {
            test("patch title stays prominent through actual item expansion width=$width scale=$scale dark=$dark failed=$failed") { offscreenTest {
                val filename = "long-漢字-name.txt"
                val path = "folder/$filename"
                val diff = Patch("", listOf(AddFileHunk(path, "  内容 α")))
                val result = if (failed) StablePatchToolExecutionResult.Failure("fixture failure")
                    else StablePatchToolExecutionResult.Success(PatchApplyResult(PatchAffectedPaths(listOf(path), emptyList(), emptyList())))
                val event = StablePatchToolEvent("patch-style", diff = diff, result = result)
                val header = PatchHistoryItemHeader(PatchHistoryItemTarget.SingleFile(filename),
                    if (failed) PatchHistoryItemStatus.Failed else PatchHistoryItemStatus.Completed, 0.milliseconds)
                val states = MutableStateFlow<PatchHistoryItemState>(PatchHistoryItemState.Collapsed(header))
                val item = object : PatchHistoryItemViewModel {
                    override val index = 1
                    override val state = states
                    override fun expand() { states.value = PatchHistoryItemState.Expanded(header, event) }
                    override fun collapse() { states.value = PatchHistoryItemState.Collapsed(header) }
                }
                val title = "${if (failed) "Failed to edit" else "Edit"} $filename"
                val scheme = if (dark) darkColorScheme() else lightColorScheme()
                val scene = OffscreenScene.create(width = width, height = 1800) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        MaterialTheme(colorScheme = scheme) {
                            val renderer = rememberComposeHistoryItemRenderer()
                            renderer.Text("Patch body reference", HistoryTextStyle.Normal)
                            HistoryItemContent(item, renderer)
                        }
                    }
                }
                try {
                    scene.settle { scene.hasDescription("Expand History item: $title") }
                    fun layout(value: String): TextLayoutResult {
                        val layouts = mutableListOf<TextLayoutResult>()
                        assertTrue(assertNotNull(scene.text(value).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                        return layouts.single()
                    }
                    val reference = layout("Patch body reference").layoutInput.style
                    val collapsed = layout(title).layoutInput.style
                    assertTrue(collapsed.fontSize.value >= reference.fontSize.value, "Collapsed title must not be smaller than body")
                    assertTrue((collapsed.fontWeight?.weight ?: 0) >= FontWeight.SemiBold.weight, "Collapsed title must be prominent")
                    assertEquals(if (failed) scheme.error else scheme.onSurface, collapsed.color)
                    val node = scene.nodes().first {
                        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Expand History item: $title") == true
                    }
                    assertTrue(assertNotNull(node.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
                    scene.key(Key.Enter)
                    scene.settle { scene.hasText("A $path") && scene.hasText("+   内容 α") }
                    val expanded = layout(title).layoutInput.style
                    val body = layout("+   内容 α").layoutInput.style
                    assertEquals(collapsed.fontSize, expanded.fontSize)
                    assertEquals(collapsed.fontWeight, expanded.fontWeight)
                    assertEquals(collapsed.color, expanded.color)
                    assertTrue(expanded.fontSize.value >= body.fontSize.value)
                    assertEquals(scheme.tertiary, body.color)
                    if (failed) assertTrue(scene.hasText("Error: fixture failure"))
                    scene.key(Key.Enter)
                    scene.settle { scene.hasDescription("Expand History item: $title") }
                } finally { scene.close() }
            } }
        }
    for (width in listOf(120, 360)) for (scale in listOf(1f, 1.5f)) {
        test("turn marker preserves duration and body text size width=$width scale=$scale") { offscreenTest {
            val scene = OffscreenScene.create(width = width) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    val renderer = rememberComposeHistoryItemRenderer()
                    renderer.Text("Body reference", HistoryTextStyle.Normal)
                    renderer.TurnMarker(1500.microseconds)
                    renderer.TurnMarker(Duration.INFINITE)
                }
            }
            try {
                scene.settle { scene.hasText("Body reference") }
                assertTrue(scene.hasText("Worked for 2ms"))
                assertTrue(scene.hasText("Worked for Infinity"))
                assertFalse(scene.hasText("---Worked for 2ms---"), "Terminal decoration must not be present")
                val dividers = scene.nodes().filter {
                    it.config.getOrNull(SemanticsProperties.TestTag) == "history-turn-divider"
                }
                assertEquals(2, dividers.size)
                assertTrue(dividers.all { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f })
                fun layout(value: String): TextLayoutResult {
                    val results = mutableListOf<TextLayoutResult>()
                    assertTrue(assertNotNull(scene.text(value).config.getOrNull(
                        SemanticsActions.GetTextLayoutResult,
                    )?.action)(results))
                    return results.single()
                }
                val body = layout("Body reference")
                val marker = layout("Worked for 2ms")
                assertEquals(body.layoutInput.style.fontSize, marker.layoutInput.style.fontSize)
                assertFalse(marker.hasVisualOverflow, "Marker overflow: ${marker.size}, lines=${marker.lineCount}")
            } finally { scene.close() }
        } }
    }
    test("stored assistant marker remains after its message and before the next entry") { offscreenTest {
        fun message(index: Int, text: String, turn: Duration?) = object : MessageHistoryItemViewModel {
            override val index = index
            override val state = MutableStateFlow<MessageHistoryItemState>(
                MessageHistoryItemState.Ready(
                    StableAssistantMessage(listOf(ContentItem.OutputText(text))),
                    elapsed = 1.milliseconds,
                    turnDuration = turn,
                ),
            )
            override suspend fun readTimestamp(): Instant? = null
        }
        val first = message(1, "FIRST_MARKER_BODY", 1500.microseconds)
        val second = message(2, "SECOND_MARKER_BODY", null)
        val scene = OffscreenScene.create(width = 360) {
            val renderer = rememberComposeHistoryItemRenderer()
            HistoryItemContent(first, renderer, null)
            HistoryItemContent(second, renderer, null)
        }
        try {
            scene.settle { scene.hasText("SECOND_MARKER_BODY") }
            val firstBounds = scene.text("FIRST_MARKER_BODY").boundsInRoot
            val markerBounds = scene.text("Worked for 2ms").boundsInRoot
            val secondBounds = scene.text("SECOND_MARKER_BODY").boundsInRoot
            assertTrue(firstBounds.bottom <= markerBounds.top)
            assertTrue(markerBounds.bottom <= secondBounds.top)
            assertEquals(1, scene.nodes().count {
                it.config.getOrNull(SemanticsProperties.TestTag) == "history-turn-divider"
            })
        } finally { scene.close() }
    } }
    test("group loading keeps its title separate from the disclosure and remains actionable") { offscreenTest {
        val group = object : WorkGroupHistoryItemViewModel {
            override val indexRange = 1..1
            override val itemCount = 1
            override val state = MutableStateFlow<WorkGroupHistoryItemState>(WorkGroupHistoryItemState.Collapsed(1.milliseconds))
            override fun expand() {}
            override fun collapse() {}
        }
        var clicks = 0
        val scene = OffscreenScene.create {
            rememberComposeHistoryItemRenderer().GroupHeading(
                group, "> literal title", 1.milliseconds, HistoryDisclosureState.Loading,
            ) { clicks++ }
        }
        try {
            scene.awaitAnimatedState("History group: > literal title", "Loading")
            val node = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History group: > literal title") == true
            }
            assertEquals("Loading", node.config.getOrNull(SemanticsProperties.StateDescription))
            assertTrue(scene.hasText("> literal title"))
            scene.clickDescription("History group: > literal title")
            assertEquals(1, clicks)
        } finally { scene.close() }
    } }

    test("history headings wrap without losing Unicode and group actions are more compact") { offscreenTest {
        val title = "完整标题与 Unicode café " .repeat(8)
        val group = object : WorkGroupHistoryItemViewModel {
            override val indexRange = 1..2
            override val itemCount = 2
            override val state = MutableStateFlow<WorkGroupHistoryItemState>(WorkGroupHistoryItemState.Collapsed(1.milliseconds))
            override fun expand() {}
            override fun collapse() {}
        }
        val scene = OffscreenScene.create(width = 280) {
            val renderer = rememberComposeHistoryItemRenderer()
            renderer.ItemHeading(title, 1.milliseconds, HistoryTextStyle.Normal)
            renderer.GroupHeading(group, "Take 2 actions", 1.milliseconds, HistoryDisclosureState.Collapsed) {}
            TextButton({}, Modifier.fillMaxWidth()) { Text("Baseline") }
        }
        try {
            scene.settle { scene.hasText(title) && scene.hasText("Baseline") }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(assertNotNull(scene.text(title).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
            assertTrue(layouts.single().lineCount > 1)
            assertFalse(layouts.single().hasVisualOverflow)
            val compact = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History group: Take 2 actions") == true
            }
            assertEquals("Collapsed", compact.config.getOrNull(SemanticsProperties.StateDescription))
            assertFalse(scene.hasText("> Take 2 actions"))
            assertFalse(scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("history-tool-disclosure-") == true
            }, "Work Group must retain its own indicator")
            val baseline = scene.nodes().first {
                it.config.getOrNull(SemanticsActions.OnClick) != null &&
                    it.children.any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Baseline" } == true }
            }
            assertTrue(compact.boundsInRoot.height < baseline.boundsInRoot.height)
        } finally { scene.close() }
    } }

    test("real folded WorkGroup mounts exact children and controlled tool/Patch expansion") { offscreenTest {
        val stored = InMemoryKodexAgentStorage.empty()
        stored.commit(
            StableUserMessage(listOf(ContentItem.InputText("First message"))),
            timestamp = Instant.fromEpochMilliseconds(1_000L),
        )
        stored.commit(
            StableTextToolEvent("demo", name = "demo", arguments = JsonObject(emptyMap()), result = "Full tool output"),
            timestamp = Instant.fromEpochMilliseconds(2_000L),
        )
        stored.commit(
            StablePatchToolEvent(
                "patch", diff = Patch("", listOf(AddFileHunk("new.txt", "line"))),
                result = StablePatchToolExecutionResult.Failure("cannot apply"),
            ),
            timestamp = Instant.fromEpochMilliseconds(3_000L),
        )
        stored.commit(
            StableUserMessage(listOf(ContentItem.InputText("Latest message"))),
            timestamp = Instant.fromEpochMilliseconds(4_000L),
        )
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
            rendered.settle { rendered.hasDescription("History group: Take 2 actions") }
            assertFalse(rendered.hasDescription("Expand History item: demo"))
            rendered.clickDescription("History group: Take 2 actions")
            rendered.settle { rendered.hasDescription("Expand History item: demo") &&
                rendered.hasDescription("Expand History item: Failed to edit new.txt") }
            assertEquals(2, rendered.nodes().count {
                it.config.getOrNull(SemanticsProperties.TestTag) == "history-tool-disclosure-Collapsed"
            })
            val children = assertIs<WorkGroupHistoryItemState.Expanded>(group.state.value).children
            assertTrue(rendered.nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History group: Take 2 actions") == true &&
                    it.config.getOrNull(SemanticsProperties.StateDescription) == "Expanded"
            })
            children.forEach { assertSame(it, seen[it.index]) }
            val tool = children.filterIsInstance<ToolHistoryItemViewModel>().single()
            val patch = children.filterIsInstance<PatchHistoryItemViewModel>().single()
            val collapsedTool = rendered.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Expand History item: demo") == true
            }
            assertTrue(assertNotNull(collapsedTool.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            rendered.key(Key.Spacebar)
            rendered.settle { rendered.hasDescription("History tool: demo") && rendered.hasText("Result") }
            rendered.key(Key.Spacebar)
            rendered.settle { rendered.hasDescription("Expand History item: demo") }
            assertIs<ToolHistoryItemState.Collapsed>(tool.state.value)
            rendered.clickDescription("Expand History item: demo")
            rendered.settle { rendered.hasDescription("History tool: demo") && rendered.hasText("Result") }
            assertIs<ToolHistoryItemState.Expanded>(tool.state.value)
            rendered.click("Result")
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
            val collapsedPatch = rendered.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)
                    ?.contains("Expand History item: Failed to edit new.txt") == true
            }
            assertTrue(assertNotNull(collapsedPatch.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            rendered.key(Key.Enter)
            rendered.settle { rendered.hasText("Error: cannot apply") }
            rendered.key(Key.Enter)
            rendered.settle { rendered.hasDescription("Expand History item: Failed to edit new.txt") }
            assertIs<PatchHistoryItemState.Collapsed>(patch.state.value)
            rendered.clickDescription("History group: Take 2 actions")
            rendered.settle { rendered.hasDescription("History group: Take 2 actions") &&
                rendered.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History group: Take 2 actions") == true &&
                    it.config.getOrNull(SemanticsProperties.StateDescription) == "Collapsed" } }
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
            scene.awaitAnimatedState("Expand History item: Failed to run: pwd", "failed; Loading")
            assertFalse(scene.hasText("Error"))
            scene.clickDescription("Expand History item: Failed to run: pwd")
            assertEquals(1, expands)
            assertEquals(1, collapses)
            scene.frame()
        } finally {
            scene.close()
            job.cancel()
        }
    } }

    test("late tool expansion does not reclaim focus after navigation") { offscreenTest {
        val job = Job()
        val header = ToolHistoryItemHeader.Summary("demo", "completed", 1.milliseconds)
        val states = MutableStateFlow<ToolHistoryItemState>(ToolHistoryItemState.Collapsed(header))
        val item = object : ToolHistoryItemViewModel {
            override val index = 42
            override val state = states
            override fun expand() { states.value = ToolHistoryItemState.Expanding(header, job) }
            override fun collapse() { states.value = ToolHistoryItemState.Collapsed(header) }
        }
        val scene = OffscreenScene.create {
            androidx.compose.foundation.layout.Column {
                HistoryItemContent(item, rememberComposeHistoryItemRenderer())
                TextButton({}) { Text("Other action") }
            }
        }
        try {
            scene.settle { scene.hasDescription("Expand History item: demo") }
            val collapsed = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Expand History item: demo") == true
            }
            assertTrue(assertNotNull(collapsed.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            scene.key(Key.Spacebar)
            scene.awaitAnimatedState("Expand History item: demo", "completed; Loading")
            assertFalse(scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("history-tool-disclosure-") == true
            }, "Loading retains its progress indicator instead of a completed or collapsed mark")
            val other = scene.nodes().first {
                it.config.getOrNull(SemanticsActions.RequestFocus) != null &&
                    it.children.any { child ->
                        child.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Other action" } == true
                    }
            }
            assertTrue(assertNotNull(other.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            states.value = ToolHistoryItemState.Expanded(
                header, StableTextToolEvent("demo", name = "demo", arguments = JsonObject(emptyMap()), result = "Done"),
            )
            scene.settle { scene.hasDescription("History tool: demo") }
            val focusedOther = scene.nodes().first {
                it.config.getOrNull(SemanticsActions.RequestFocus) != null &&
                    it.children.any { child ->
                        child.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Other action" } == true
                    }
            }
            assertEquals(true, focusedOther.config.getOrNull(SemanticsProperties.Focused))
        } finally {
            scene.close()
            job.cancel()
        }
    } }

    test("keyboard can cancel an in-flight disclosure and continue toggling") { offscreenTest {
        val job = Job()
        val header = ToolHistoryItemHeader.Summary("demo", "completed", 1.milliseconds)
        val states = MutableStateFlow<ToolHistoryItemState>(ToolHistoryItemState.Collapsed(header))
        val item = object : ToolHistoryItemViewModel {
            override val index = 42
            override val state = states
            override fun expand() { states.value = ToolHistoryItemState.Expanding(header, job) }
            override fun collapse() { states.value = ToolHistoryItemState.Collapsed(header) }
        }
        val scene = OffscreenScene.create { HistoryItemContent(item, rememberComposeHistoryItemRenderer()) }
        try {
            scene.settle { scene.hasDescription("Expand History item: demo") }
            val collapsed = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Expand History item: demo") == true
            }
            assertTrue(assertNotNull(collapsed.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            scene.key(Key.Spacebar)
            scene.awaitAnimatedState("Expand History item: demo", "completed; Loading")
            scene.key(Key.Enter)
            scene.settle { item.state.value is ToolHistoryItemState.Collapsed }
            scene.key(Key.Spacebar)
            scene.awaitAnimatedState("Expand History item: demo", "completed; Loading")
            scene.key(Key.Spacebar)
            scene.settle { item.state.value is ToolHistoryItemState.Collapsed }
        } finally {
            scene.close()
            job.cancel()
        }
    } }

    test("a rejected disclosure action does not trap keyboard focus") { offscreenTest {
        val header = ToolHistoryItemHeader.Summary("demo", "completed", 1.milliseconds)
        var attempts = 0
        val item = object : ToolHistoryItemViewModel {
            override val index = 42
            override val state = MutableStateFlow<ToolHistoryItemState>(ToolHistoryItemState.Collapsed(header))
            override fun expand() { attempts++ }
            override fun collapse() {}
        }
        val scene = OffscreenScene.create { HistoryItemContent(item, rememberComposeHistoryItemRenderer()) }
        try {
            scene.settle { scene.hasDescription("Expand History item: demo") }
            val collapsed = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Expand History item: demo") == true
            }
            assertTrue(assertNotNull(collapsed.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            scene.key(Key.Spacebar)
            scene.settle { attempts == 1 }
            scene.key(Key.Spacebar)
            scene.settle { attempts == 2 }
        } finally { scene.close() }
    } }

    test("a loading disclosure focused later retains focus when its details arrive") { offscreenTest {
        val job = Job()
        val header = ToolHistoryItemHeader.Summary("demo", "completed", 1.milliseconds)
        val states = MutableStateFlow<ToolHistoryItemState>(ToolHistoryItemState.Expanding(header, job))
        val item = object : ToolHistoryItemViewModel {
            override val index = 42
            override val state = states
            override fun expand() {}
            override fun collapse() { states.value = ToolHistoryItemState.Collapsed(header) }
        }
        val scene = OffscreenScene.create { HistoryItemContent(item, rememberComposeHistoryItemRenderer()) }
        try {
            scene.awaitAnimatedState("Expand History item: demo", "completed; Loading")
            val loading = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Expand History item: demo") == true
            }
            assertTrue(assertNotNull(loading.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            states.value = ToolHistoryItemState.Expanded(
                header, StableTextToolEvent("demo", name = "demo", arguments = JsonObject(emptyMap()), result = "Done"),
            )
            scene.settle { scene.hasDescription("History tool: demo") }
            val expanded = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History tool: demo") == true
            }
            assertEquals(true, expanded.config.getOrNull(SemanticsProperties.Focused))
        } finally {
            scene.close()
            job.cancel()
        }
    } }

    test("loading disclosure does not reclaim focus moved away before details arrive") { offscreenTest {
        val job = Job()
        val header = ToolHistoryItemHeader.Summary("demo", "completed", 1.milliseconds)
        val states = MutableStateFlow<ToolHistoryItemState>(ToolHistoryItemState.Expanding(header, job))
        val item = object : ToolHistoryItemViewModel {
            override val index = 42
            override val state = states
            override fun expand() {}
            override fun collapse() { states.value = ToolHistoryItemState.Collapsed(header) }
        }
        val scene = OffscreenScene.create {
            androidx.compose.foundation.layout.Column {
                HistoryItemContent(item, rememberComposeHistoryItemRenderer())
                TextButton({}) { Text("Other action") }
            }
        }
        try {
            scene.awaitAnimatedState("Expand History item: demo", "completed; Loading")
            val loading = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Expand History item: demo") == true
            }
            assertTrue(assertNotNull(loading.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            val other = scene.nodes().first {
                it.config.getOrNull(SemanticsActions.RequestFocus) != null &&
                    it.children.any { child ->
                        child.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Other action" } == true
                    }
            }
            assertTrue(assertNotNull(other.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            states.value = ToolHistoryItemState.Expanded(
                header, StableTextToolEvent("demo", name = "demo", arguments = JsonObject(emptyMap()), result = "Done"),
            )
            scene.settle { scene.hasDescription("History tool: demo") }
            val focusedOther = scene.nodes().first {
                it.config.getOrNull(SemanticsActions.RequestFocus) != null &&
                    it.children.any { child ->
                        child.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Other action" } == true
                    }
            }
            assertEquals(true, focusedOther.config.getOrNull(SemanticsProperties.Focused))
        } finally {
            scene.close()
            job.cancel()
        }
    } }

    test("an externally started disclosure keeps its already focused header") { offscreenTest {
        val job = Job()
        val header = ToolHistoryItemHeader.Summary("demo", "completed", 1.milliseconds)
        val states = MutableStateFlow<ToolHistoryItemState>(ToolHistoryItemState.Collapsed(header))
        val item = object : ToolHistoryItemViewModel {
            override val index = 42
            override val state = states
            override fun expand() { states.value = ToolHistoryItemState.Expanding(header, job) }
            override fun collapse() { states.value = ToolHistoryItemState.Collapsed(header) }
        }
        val scene = OffscreenScene.create { HistoryItemContent(item, rememberComposeHistoryItemRenderer()) }
        try {
            scene.settle { scene.hasDescription("Expand History item: demo") }
            val collapsed = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Expand History item: demo") == true
            }
            assertTrue(assertNotNull(collapsed.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            item.expand()
            scene.awaitAnimatedState("Expand History item: demo", "completed; Loading")
            states.value = ToolHistoryItemState.Expanded(
                header, StableTextToolEvent("demo", name = "demo", arguments = JsonObject(emptyMap()), result = "Done"),
            )
            scene.settle { scene.hasDescription("History tool: demo") }
            val expanded = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History tool: demo") == true
            }
            assertEquals(true, expanded.config.getOrNull(SemanticsProperties.Focused))
        } finally {
            scene.close()
            job.cancel()
        }
    } }

    test("an externally cancelled load returns focus to the collapsed header") { offscreenTest {
        val job = Job()
        val header = ToolHistoryItemHeader.Summary("demo", "completed", 1.milliseconds)
        val states = MutableStateFlow<ToolHistoryItemState>(ToolHistoryItemState.Collapsed(header))
        val item = object : ToolHistoryItemViewModel {
            override val index = 42
            override val state = states
            override fun expand() { states.value = ToolHistoryItemState.Expanding(header, job) }
            override fun collapse() { states.value = ToolHistoryItemState.Collapsed(header) }
        }
        val scene = OffscreenScene.create { HistoryItemContent(item, rememberComposeHistoryItemRenderer()) }
        try {
            scene.settle { scene.hasDescription("Expand History item: demo") }
            val collapsed = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Expand History item: demo") == true
            }
            assertTrue(assertNotNull(collapsed.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            item.expand()
            scene.awaitAnimatedState("Expand History item: demo", "completed; Loading")
            item.collapse()
            scene.settle { item.state.value is ToolHistoryItemState.Collapsed }
            scene.key(Key.Spacebar)
            scene.awaitAnimatedState("Expand History item: demo", "completed; Loading")
        } finally {
            scene.close()
            job.cancel()
        }
    } }
}

private suspend fun OffscreenScene.awaitAnimatedState(description: String, state: String) {
    withTimeout(10_000) {
        do {
            advance()
            delay(1)
        } while (nodes().none {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true &&
                    it.config.getOrNull(SemanticsProperties.StateDescription) == state &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null
            })
    }
}
