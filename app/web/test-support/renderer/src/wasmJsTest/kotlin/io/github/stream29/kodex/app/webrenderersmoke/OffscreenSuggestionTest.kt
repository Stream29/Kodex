package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.compose.rememberComposeSuggestionRenderer
import io.github.stream29.kodex.cli.agent.*
import io.github.stream29.kodex.cli.runtimeconfiguration.*
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.tool.multiagent.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path
import kotlin.test.*

val offscreenSuggestionTest by testSuite {
    test("rejection submit is compact and refuses a retained disabled action") { offscreenTest {
        val enabled = mutableStateOf(true)
        var calls = 0
        val scene = OffscreenScene.create(width = 400, height = 160) {
            rememberComposeSuggestionRenderer().Frame("compact-reject") { controls ->
                controls.RejectSubmit(enabled.value) { calls++ }
            }
        }
        fun reject(): SemanticsNode = scene.nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                node.suggestionInputNodes().any { child ->
                    child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Submit rejection" } == true
                }
        }
        try {
            scene.settle { scene.hasText("Submit rejection") }
            assertTrue(reject().boundsInWindow.height in 32f..36f, "Reject submission should be compact.")
            val retained = assertNotNull(reject().config.getOrNull(SemanticsActions.OnClick)?.action)
            scene.click("Submit rejection")
            assertEquals(1, calls)
            enabled.value = false
            scene.settle { SemanticsProperties.Disabled in reject().config }
            retained()
            assertEquals(1, calls)
        } finally { scene.close() }
    } }
    test("working directory action is compact and rejects stale disabled callbacks") { offscreenTest {
        val enabled = mutableStateOf(true)
        val cwd = mutableStateOf(Path("/short"))
        var calls = 0
        val menu = RuntimeConfigurationMenu(
            "model", listOf(RuntimeMenuEntry.Command("model", "Model", true) {}),
            "mode", listOf(RuntimeMenuEntry.Command("mode", "Mode", true) {}),
        )
        val scene = OffscreenScene.create(width = 480) {
            rememberComposeSuggestionRenderer().Frame("compact-directory") { controls ->
                controls.Configuration(menu, cwd.value, enabled.value) { calls++ }
            }
        }
        fun descendants(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::descendants)
        fun directory(): SemanticsNode = scene.nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                descendants(node).any { child ->
                    child.config.getOrNull(SemanticsProperties.Text)?.any {
                        it.text == "Working directory: ${cwd.value}"
                    } == true
                }
        }
        try {
            scene.settle { scene.hasText("Working directory: /short") }
            val button = directory()
            assertTrue(button.boundsInWindow.width in 32f..<480f, "The directory action must not fill the entire row.")
            assertTrue(button.boundsInWindow.height >= 32f)
            val retained = assertNotNull(button.config.getOrNull(SemanticsActions.OnClick)?.action)
            scene.click("Working directory: /short")
            assertEquals(1, calls)
            enabled.value = false
            cwd.value = Path("/" + "other-long-directory-segment-".repeat(5))
            scene.settle { scene.hasText("Working directory: ${cwd.value}") &&
                SemanticsProperties.Disabled in directory().config }
            assertTrue(directory().boundsInWindow.height >= 32f)
            retained()
            assertEquals(1, calls)
        } finally { scene.close() }
    } }
    test("compact decisions retain button semantics and description while reducing height") { offscreenTest {
        val enabled = mutableStateOf(true)
        var calls = 0
        val scene = OffscreenScene.create(width = 480) {
            rememberComposeSuggestionRenderer().Frame("density") { controls ->
                controls.Decision("Decision", "Complete description", true, enabled.value) { calls++ }
                OutlinedButton({}, Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) { Text("Baseline"); Text("Complete description") }
                }
            }
        }
        fun decisionNodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::decisionNodes)
        fun button(label: String): SemanticsNode = scene.nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                decisionNodes(node).any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true }
        }
        try {
            scene.settle { scene.hasText("Decision") && scene.hasText("Baseline") }
            assertFalse(scene.hasText("● Decision"))
            val decision = button("Decision")
            assertEquals(true, decision.config.getOrNull(SemanticsProperties.Selected))
            assertTrue(decision.boundsInRoot.height < button("Baseline").boundsInRoot.height)
            val retained = assertNotNull(decision.config.getOrNull(SemanticsActions.OnClick)?.action)
            scene.click("Decision")
            assertEquals(1, calls)
            enabled.value = false
            scene.settle { SemanticsProperties.Disabled in button("Decision").config }
            retained()
            assertEquals(1, calls)
        } finally { scene.close() }
    } }

    test("task order and three-level menu reuse preserve latest cwd and mode while a tier leaf edits one tuple") { suggestionScene { ports, vm, _ ->
        val browsed = mutableListOf<String>()
        val scene = OffscreenScene.create { SuggestionHost(vm, onBrowse = browsed::add) }
        try {
            scene.settle { scene.hasDescription("Runtime model: orphan provider-effort fast") }
            assertTrue(scene.text("First task").boundsInWindow.top < scene.text("Second task").boundsInWindow.top)
            assertTrue(ports.creations.isEmpty())
            val initial = vm.pendingSuggestion()
            scene.clickDescription("Runtime model: orphan provider-effort fast")
            scene.settle { scene.hasDescription("Runtime model: catalog") }
            scene.clickDescription("Runtime model: catalog")
            scene.settle { scene.hasDescription("Runtime model: high") }
            scene.clickDescription("Runtime model: high")
            scene.settle { scene.hasDescription("Runtime model: fast") }
            assertEquals(initial, vm.pendingSuggestion())
            assertTrue(vm.updateWorkingDirectory(initial.callId, Path("/new-directory")))
            assertTrue(vm.updateRequestUserInputMode(initial.callId, RequestUserInputMode.NoQuestion))
            scene.settle { scene.hasText("Working directory: /new-directory") }
            val before = vm.pendingSuggestion()
            scene.clickDescription("Runtime model: fast")
            scene.settle { vm.pendingSuggestion().revision == before.revision + 1 }
            assertEquals(before.configuration.copy(
                model = OpenAiModelId("catalog"), reasoningEffort = ReasoningEffort.High, serviceTier = ServiceTier.Fast,
            ), vm.pendingSuggestion().configuration)
            scene.clickDescription("Questions: no question")
            scene.settle { scene.hasDescription("Questions: ask user") }
            scene.clickDescription("Questions: ask user")
            scene.settle { vm.pendingSuggestion().configuration.requestUserInputMode == RequestUserInputMode.AskUser }
            scene.click("Working directory: /new-directory")
            assertEquals(listOf("call-suggestion"), browsed)
            assertTrue(ports.creations.isEmpty())
            assertTrue(ports.completed.isEmpty())
            assertEquals(1, ports.defaultReads)
            scene.frame()
        } finally { scene.close() }
    } }

    test("missing metadata and Escape never correct the saved unsupported values") { suggestionScene { ports, vm, _ ->
        ports.models.value = emptyList()
        val scene = OffscreenScene.create { SuggestionHost(vm) }
        try {
            scene.settle { scene.hasDescription("Runtime model: orphan provider-effort fast") }
            val initial = vm.pendingSuggestion()
            scene.clickDescription("Runtime model: orphan provider-effort fast")
            scene.settle { scene.hasDescription("Runtime model: orphan") }
            scene.clickDescription("Runtime model: orphan")
            scene.settle { scene.hasDescription("Runtime model: provider-effort") }
            scene.clickDescription("Runtime model: provider-effort")
            scene.settle { scene.hasDescription("Runtime model: default") }
            assertFalse(scene.hasDescription("Runtime model: fast"))
            scene.escape()
            scene.settle { scene.hasDescription("Runtime model: provider-effort") && !scene.hasDescription("Runtime model: default") }
            scene.escape()
            scene.settle { scene.hasDescription("Runtime model: orphan") }
            scene.escape()
            scene.settle { !scene.hasDescription("Runtime model: orphan") }
            assertEquals(initial, vm.pendingSuggestion())
            assertTrue(ports.creations.isEmpty())
            assertTrue(ports.completed.isEmpty())
        } finally { scene.close() }
    } }

    test("Reject only opens feedback and explicit rejection keeps raw nonblank text without creating Sessions") { suggestionScene { ports, vm, _ ->
        val scene = OffscreenScene.create { SuggestionHost(vm) }
        try {
            scene.settle { scene.hasText("Reject") }
            scene.click("Reject")
            scene.settle { scene.hasDescription("Suggestion feedback (optional)") }
            assertTrue(vm.pendingSuggestion().rejecting)
            assertTrue(ports.completed.isEmpty())
            scene.settle { scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.Focused) == true &&
                    it.config.getOrNull(SemanticsProperties.EditableText)?.text == ""
            } }
            scene.editSuggestionFeedback("  not now  ")
            scene.settle { vm.pendingSuggestion().feedback == "  not now  " }
            scene.showSuggestionText("Submit rejection")
            scene.click("Submit rejection")
            scene.settle { scene.hasText("No pending suggestion") }
            assertTrue(ports.creations.isEmpty())
            val response = (ports.completed.single() as StableSuggestSubagentTaskToolEvent).result as StableSuggestSubagentTaskResult.Completed
            assertEquals("  not now  ", assertIs<SuggestSubagentTaskResponse.Rejected>(response.response).feedback)
            assertEquals(listOf("complete", "resume"), ports.order)
        } finally { scene.close() }
    } }

    test("old Accept revision and old-call menu leaf cannot borrow a newer configuration") { suggestionScene { ports, vm, _ ->
        var accept: (() -> Unit)? = null
        var capturedMenu: RuntimeConfigurationMenu? = null
        val scene = OffscreenScene.create {
            val graphical = rememberComposeSuggestionRenderer()
            val renderer = object : SuggestionRenderer {
                @Composable override fun Frame(callId: String, content: @Composable (SuggestionRenderScope) -> Unit) {
                    graphical.Frame(callId) { controls ->
                        content(object : SuggestionRenderScope by controls {
                            @Composable override fun Configuration(menu: RuntimeConfigurationMenu, cwd: Path, enabled: Boolean, onBrowse: () -> Unit) {
                                capturedMenu = menu
                                controls.Configuration(menu, cwd, enabled, onBrowse)
                            }
                            @Composable override fun Decision(
                                label: String, description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit,
                            ) {
                                if (label == "Accept") accept = onClick
                                controls.Decision(label, description, selected, enabled, onClick)
                            }
                        })
                    }
                }
            }
            SuggestionHost(vm, renderer)
        }
        try {
            scene.settle { accept != null && capturedMenu != null }
            val oldAccept = assertNotNull(accept)
            val model = assertNotNull(capturedMenu).models.filterIsInstance<RuntimeMenuEntry.Branch>().first()
            val effort = model.children().filterIsInstance<RuntimeMenuEntry.Branch>().first()
            val leaf = effort.children().filterIsInstance<RuntimeMenuEntry.Command>().first()
            assertTrue(vm.updateWorkingDirectory("call-suggestion", Path("/newer")))
            oldAccept()
            scene.settle { vm.pendingSuggestion().configuration.cwd == Path("/newer") }
            assertTrue(ports.creations.isEmpty())
            assertFalse(vm.pendingSuggestion().submitting)
            ports.pending.value = suggestionEvent("replacement", "Replacement task")
            scene.settle { vm.pendingSuggestion().callId == "replacement" && scene.hasText("Replacement task") }
            leaf.onSelect()
            oldAccept()
            scene.settle { true }
            assertEquals(ports.defaults, vm.pendingSuggestion().configuration)
            assertEquals(0L, vm.pendingSuggestion().revision)
            assertTrue(ports.creations.isEmpty())
            assertTrue(ports.completed.isEmpty())
        } finally { scene.close() }
    } }

    test("accepted creation survives panel unmount once and keeps the Agent-owned child observing") { suggestionScene { ports, vm, owner ->
        ports.creationGate = CompletableDeferred()
        val visible = mutableStateOf(true)
        val scene = OffscreenScene.create { if (visible.value) SuggestionHost(vm) else UiText("Unmounted") }
        try {
            scene.settle { scene.hasText("Accept") }
            val captured = vm.pendingSuggestion()
            scene.click("Accept")
            scene.settle { ports.creations.size == 1 && vm.pendingSuggestion().submitting }
            assertFalse(vm.updateFeedback(captured.callId, "late"))
            assertFalse(vm.updateWorkingDirectory(captured.callId, Path("/late")))
            scene.click("Accept")
            scene.settle { true }
            assertEquals(1, ports.creations.size)
            visible.value = false
            scene.settle { scene.hasText("Unmounted") }
            assertEquals(0, ports.cancellations)
            ports.creationGate!!.complete(Unit)
            scene.settle { ports.order == listOf("create", "complete", "resume") }
            assertEquals(listOf(captured.arguments to captured.configuration), ports.creations)
            val event = assertIs<StableSuggestSubagentTaskToolEvent>(ports.completed.single())
            val response = assertIs<StableSuggestSubagentTaskResult.Completed>(event.result).response
            assertEquals(ports.created, assertIs<SuggestSubagentTaskResponse.Accepted>(response).sessions)
            assertEquals(0, ports.cancellations)
            ports.pending.value = suggestionEvent("next")
            scene.settle { vm.pendingSuggestion().callId == "next" }
            assertTrue(owner.coroutineContext.job.isActive)
        } finally { scene.close() }
    } }

    test("failed completion after creation is not rolled back or retried and a later explicit Reject creates nothing") { suggestionScene { ports, vm, _ ->
        ports.completionFailure = IllegalStateException("unknown receipt")
        val scene = OffscreenScene.create { SuggestionHost(vm) }
        try {
            scene.settle { scene.hasText("Accept") }
            scene.click("Accept")
            scene.settle { ports.completed.size == 1 && !vm.pendingSuggestion().submitting }
            assertEquals(1, ports.creations.size)
            assertEquals(listOf("create", "complete"), ports.order)
            assertTrue(scene.hasText("First task"))
            ports.completionFailure = null
            scene.click("Reject")
            scene.settle { scene.hasDescription("Suggestion feedback (optional)") }
            scene.showSuggestionText("Submit rejection")
            scene.click("Submit rejection")
            scene.settle { scene.hasText("No pending suggestion") }
            assertEquals(1, ports.creations.size)
            assertEquals(2, ports.completed.size)
            val response = (ports.completed.last() as StableSuggestSubagentTaskToolEvent).result as StableSuggestSubagentTaskResult.Completed
            assertNull(assertIs<SuggestSubagentTaskResponse.Rejected>(response.response).feedback)
            assertEquals(listOf("create", "complete", "complete", "resume"), ports.order)
        } finally { scene.close() }
    } }
}

@Composable
private fun SuggestionHost(
    vm: SuggestSubagentTaskViewModel,
    renderer: SuggestionRenderer = rememberComposeSuggestionRenderer(), onBrowse: (String) -> Unit = {},
) {
    val state = vm.state.collectAsState().value
    if (state is SuggestSubagentTaskState.Pending) SuggestSubagentTaskContent(vm, state, renderer, onBrowse)
    else UiText("No pending suggestion")
}

private fun OffscreenScene.editSuggestionFeedback(value: String) {
    val root = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Suggestion feedback (optional)") == true }
    val action = root.suggestionInputNodes().mapNotNull { it.config.getOrNull(SemanticsActions.SetText)?.action }.firstOrNull()
    assertTrue(assertNotNull(action)(AnnotatedString(value)))
}

private fun SemanticsNode.suggestionInputNodes(): List<SemanticsNode> = listOf(this) + children.flatMap { it.suggestionInputNodes() }

private suspend fun OffscreenScene.showSuggestionText(label: String) {
    repeat(8) {
        val scroll = nodes().first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
        val target = text(label).boundsInWindow
        val viewport = scroll.boundsInWindow
        if (target.height > 0 && target.top >= viewport.top && target.bottom <= viewport.bottom) return
        assertTrue(assertNotNull(scroll.config.getOrNull(SemanticsActions.ScrollBy)?.action)(0f, 200f))
        settle { true }
    }
    error("Suggestion action was not scrolled into view: $label")
}

private fun SuggestSubagentTaskViewModel.pendingSuggestion(): SuggestSubagentTaskState.Pending = assertIs(state.value)

private suspend fun suggestionScene(block: suspend (SuggestionPorts, SuggestSubagentTaskViewModel, CoroutineScope) -> Unit) = offscreenTest {
    val ports = SuggestionPorts()
    val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
    val vm = createSuggestSubagentTaskViewModel(ports, owner)
    try { block(ports, vm, owner) } finally { vm.close(); owner.coroutineContext.job.cancelAndJoin() }
}

internal fun suggestionEvent(callId: String = "call-suggestion", name: String = "First task") = PendingSuggestSubagentTaskToolEvent(
    callId = callId, arguments = SuggestSubagentTaskArgs(listOf(
        SuggestedSubagentTask(name, "First prompt"), SuggestedSubagentTask("Second task", "Second prompt"),
    )),
)

internal class SuggestionPorts : SuggestSubagentTaskDependencies {
    override val pending = MutableStateFlow<PendingSuggestSubagentTaskToolEvent?>(suggestionEvent())
    override val models = MutableStateFlow(listOf(ModelInfo(
        OpenAiModelId("catalog"), "Catalog",
        supportedReasoningLevels = listOf(ReasoningEffortPreset(ReasoningEffort.Low, "Low"), ReasoningEffortPreset(ReasoningEffort.High, "High")),
        serviceTiers = listOf(ModelServiceTier(ServiceTier.Fast.requestValue, "Fast", "Fast")),
    )))
    val defaults = SuggestedSessionConfiguration(
        OpenAiModelId("orphan"), ReasoningEffort.Custom("provider-effort"), ServiceTier.Fast,
        Path("/fixture"), RequestUserInputMode.AskUser,
    )
    val order = mutableListOf<String>()
    val creations = mutableListOf<Pair<SuggestSubagentTaskArgs, SuggestedSessionConfiguration>>()
    val completed = mutableListOf<StableCleanEvent.CompletedTool>()
    val created = listOf(SuggestedSessionMeta("fixture://one", "First"), SuggestedSessionMeta("fixture://two", "Second"))
    var defaultReads = 0
    var creationGate: CompletableDeferred<Unit>? = null
    var completionFailure: Throwable? = null
    var cancellations = 0
    override fun defaultConfiguration(): SuggestedSessionConfiguration { defaultReads++; return defaults }
    override suspend fun createSessions(arguments: SuggestSubagentTaskArgs, configuration: SuggestedSessionConfiguration): List<SuggestedSessionMeta> {
        creations += arguments to configuration
        order += "create"
        try { creationGate?.await() } catch (failure: CancellationException) { cancellations++; throw failure }
        return created
    }
    override suspend fun completeToolCall(completed: StableCleanEvent.CompletedTool): Int {
        this.completed += completed
        order += "complete"
        completionFailure?.let { throw it }
        return 1
    }
    override fun resumeRuntime() { order += "resume" }
}
