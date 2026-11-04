package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.compose.rememberComposeUserInputRenderer
import io.github.stream29.kodex.cli.agent.*
import io.github.stream29.kodex.tool.requestuserinput.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenUserInputTest by testSuite {
    test("secret metadata freeform keeps keyboard submission and in-flight disabled guards") { userInputScene { ports, vm, _ ->
        ports.pending.value = PendingRequestUserInputToolEvent("secret-input", arguments = RequestUserInputArgs(listOf(
            RequestUserInputQuestion("secret", "Secret", "Value", isSecret = true),
        )))
        ports.gate = CompletableDeferred()
        val scene = OffscreenScene.create { UserInputHost(vm) }
        try {
            scene.settle { scene.hasDescription("Answer for secret") && scene.hasFocusedInput("") }
            scene.setUserAnswer("secret", "test value")
            val edit = assertNotNull(scene.nodes().first {
                it.config.getOrNull(SemanticsActions.SetText) != null
            }.config.getOrNull(SemanticsActions.SetText)?.action)
            scene.doneUserAnswer("secret")
            scene.settle { scene.overlayTextFocused("Submit") }
            scene.key(Key.Spacebar)
            scene.settle { scene.hasText("Submitting…") && ports.completed.size == 1 }
            edit(AnnotatedString("late edit"))
            scene.key(Key.Enter)
            scene.key(Key.Tab)
            scene.settle { true }
            assertEquals(RequestUserInputDraftAnswer.FreeForm("test value"), vm.pendingInput().answers["secret"])
            assertEquals(1, ports.completed.size)
            assertFalse(vm.pendingInput().canSubmit)
            ports.gate!!.complete(Unit)
            scene.settle { scene.hasText("No pending input") }
            assertEquals(listOf("complete", "resume"), ports.order)
        } finally { scene.close() }
    } }

    test("pending options Other and freeform traverse and activate without pointer input") { userInputScene { ports, vm, _ ->
        val scene = OffscreenScene.create { UserInputHost(vm) }
        try {
            scene.settle { scene.overlayTextFocused("Current module") }
            scene.key(Key.Tab)
            scene.settle { scene.overlayTextFocused("Whole workspace") }
            scene.key(Key.Tab, shift = true)
            scene.settle { scene.overlayTextFocused("Current module") }
            scene.key(Key.Tab)
            scene.key(Key.Tab)
            scene.settle { scene.overlayTextFocused("Other") }
            scene.key(Key.Spacebar)
            scene.settle { scene.hasDescription("Answer for scope") && scene.hasFocusedInput("") }
            scene.setUserAnswer("scope", "keyboard choice")
            scene.doneUserAnswer("scope")
            scene.settle { scene.hasFocusedInput("") }
            scene.setUserAnswer("note", "keyboard note")
            scene.doneUserAnswer("note")
            scene.settle { scene.overlayTextFocused("Submit") }
            scene.key(Key.Enter)
            scene.settle { scene.hasText("No pending input") }
            assertEquals(listOf("complete", "resume"), ports.order)
            assertEquals(1, ports.completed.size)
            assertTrue(vm.state.value !is RequestUserInputState.Pending)
        } finally { scene.close() }
    } }

    test("submit action is compact and keeps exact focus and disabled admission") { offscreenTest {
        val enabled = mutableStateOf(true)
        var calls = 0
        val scene = OffscreenScene.create(width = 400, height = 160) {
            rememberComposeUserInputRenderer().Frame("compact-submit", 0, 1) { controls ->
                controls.Submit("Go", enabled.value) { calls++ }
                LaunchedEffect(Unit) { controls.requestFocus(UserInputFocusTarget.Submit) }
            }
        }
        fun submit(): SemanticsNode = scene.nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                node.inputNodes().any { child ->
                    child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Go" } == true
                }
        }
        try {
            scene.settle { scene.hasText("Go") && submit().config.getOrNull(SemanticsProperties.Focused) == true }
            assertTrue(submit().boundsInWindow.height in 32f..36f, "Submit should use a compact filled target.")
            val retained = assertNotNull(submit().config.getOrNull(SemanticsActions.OnClick)?.action)
            scene.key(Key.Enter)
            scene.settle { calls == 1 }
            enabled.value = false
            scene.settle { SemanticsProperties.Disabled in submit().config }
            retained()
            assertEquals(1, calls)
        } finally { scene.close() }
    } }
    test("compact option has radio semantics, full description and less height than the original button") { offscreenTest {
        val description = "Only this module"
        val scene = OffscreenScene.create(width = 480) {
            rememberComposeUserInputRenderer().Frame("density", 0, 1) { controls ->
                controls.question(0, "density").Option("Compact", description, false, true, false, true) {}
                OutlinedButton({}, Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) { Text("Baseline"); Text(description) }
                }
            }
        }
        try {
            scene.settle { scene.hasText("Compact") && scene.hasText("Baseline") }
            assertFalse(scene.hasText("○ Compact"))
            val compact = scene.nodes().single { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
            val baseline = scene.nodes().first { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.inputNodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "Baseline" } == true }
            }
            assertEquals(false, compact.config.getOrNull(SemanticsProperties.Selected))
            assertTrue(compact.boundsInRoot.height < baseline.boundsInRoot.height)
            assertEquals(2, scene.nodes().count { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == description } == true })
        } finally { scene.close() }
    } }

    test("compact option is keyboard actionable and a retained semantics action obeys current disabled") { offscreenTest {
        val enabled = mutableStateOf(true)
        val selected = mutableStateOf(false)
        var calls = 0
        val scene = OffscreenScene.create {
            rememberComposeUserInputRenderer().Frame("keyboard", 0, 1) { controls ->
                controls.question(0, "keyboard").Option("Choose", "Complete description", selected.value, enabled.value, true, true) {
                    calls++; selected.value = true
                }
            }
        }
        try {
            scene.settle { scene.nodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true } }
            val row = scene.nodes().single { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
            val retained = assertNotNull(row.config.getOrNull(SemanticsActions.OnClick)?.action)
            scene.key(Key.Spacebar)
            scene.settle { scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                    it.config.getOrNull(SemanticsProperties.Selected) == true
            } }
            assertFalse(scene.hasText("● Choose"))
            assertEquals(1, calls)
            enabled.value = false
            scene.settle { scene.nodes().any { SemanticsProperties.Disabled in it.config } }
            retained()
            scene.key(Key.Enter)
            scene.settle { true }
            assertEquals(1, calls)
        } finally { scene.close() }
    } }

    test("shared questions select exact answers and focus text then Submit, including same-frame Done") { userInputScene { ports, vm, _ ->
        val targets = mutableListOf<UserInputFocusTarget>()
        val scene = OffscreenScene.create { UserInputHost(vm, targets = targets) }
        try {
            scene.settle { scene.hasText("Current module") && scene.hasDescription("Answer for note") }
            assertFalse(vm.pendingInput().canSubmit)
            scene.click("Current module")
            scene.settle { vm.pendingInput().answers["scope"] is RequestUserInputDraftAnswer.Option }
            assertEquals(UserInputFocusTarget.FreeForm(1), targets.last())
            scene.settle { scene.hasFocusedInput("") }
            scene.setUserAnswer("note", "   ")
            scene.doneUserAnswer("note")
            assertEquals(UserInputFocusTarget.FreeForm(1), targets.last())
            assertFalse(vm.pendingInput().canSubmit)
            scene.setUserAnswer("note", "  details  ")
            scene.doneUserAnswer("note")
            scene.settle { vm.pendingInput().canSubmit && targets.last() == UserInputFocusTarget.Submit }
            scene.click("Submit")
            scene.settle { scene.hasText("No pending input") }
            val completed = assertIs<StableRequestUserInputToolEvent>(ports.completed.single())
            val response = assertIs<StableRequestUserInputResult.Answered>(completed.result).response
            assertEquals(listOf("scope", "note"), response.answers.keys.toList())
            assertEquals(listOf("Current module"), response.answers.getValue("scope").answers)
            assertEquals(listOf("user_note: details"), response.answers.getValue("note").answers)
            assertEquals("call-input", completed.callId)
            assertEquals(listOf("complete", "resume"), ports.order)
            scene.frame()
        } finally { scene.close() }
    } }

    test("Other creates controlled text without silently answering or submitting") { userInputScene { ports, vm, _ ->
        val targets = mutableListOf<UserInputFocusTarget>()
        val scene = OffscreenScene.create { UserInputHost(vm, targets = targets) }
        try {
            scene.settle { scene.hasText("Other") }
            scene.click("Other")
            scene.settle { scene.hasDescription("Answer for scope") && scene.hasText("Other") }
            assertEquals(RequestUserInputDraftAnswer.FreeForm(""), vm.pendingInput().answers["scope"])
            assertEquals(UserInputFocusTarget.FreeForm(0), targets.last())
            scene.doneUserAnswer("scope")
            assertFalse(vm.pendingInput().canSubmit)
            assertTrue(ports.completed.isEmpty())
            scene.setUserAnswer("scope", "chosen text")
            scene.doneUserAnswer("scope")
            scene.settle { targets.last() == UserInputFocusTarget.FreeForm(1) }
            assertEquals(RequestUserInputDraftAnswer.FreeForm("chosen text"), vm.pendingInput().answers["scope"])
            assertFalse(vm.pendingInput().canSubmit)
            assertTrue(ports.completed.isEmpty())
        } finally { scene.close() }
    } }

    test("old option and text callbacks cannot edit a replacement call or move focus") { userInputScene { ports, vm, _ ->
        var option: (() -> Unit)? = null
        var edit: ((String) -> Boolean)? = null
        val targets = mutableListOf<UserInputFocusTarget>()
        val scene = OffscreenScene.create {
            val graphical = rememberComposeUserInputRenderer()
            val renderer = object : UserInputRenderer {
                @Composable
                override fun Frame(
                    callId: String, revision: Long, questionCount: Int, content: @Composable (UserInputRenderScope) -> Unit,
                ) {
                    graphical.Frame(callId, revision, questionCount) { controls ->
                        content(object : UserInputRenderScope by controls {
                            override fun requestFocus(target: UserInputFocusTarget) { targets += target; controls.requestFocus(target) }
                            override fun question(index: Int, questionId: String): UserInputQuestionRenderer {
                                val original = controls.question(index, questionId)
                                return object : UserInputQuestionRenderer by original {
                                    @Composable override fun Option(
                                        label: String, description: String, selected: Boolean, enabled: Boolean,
                                        autoFocus: Boolean, first: Boolean, onClick: () -> Unit,
                                    ) {
                                        if (callId == "call-input" && first) option = onClick
                                        original.Option(label, description, selected, enabled, autoFocus, first, onClick)
                                    }
                                    @Composable override fun FreeForm(
                                        text: String, enabled: Boolean, autoFocus: Boolean,
                                        onChange: (String) -> Boolean, onSubmitted: () -> Unit,
                                    ) {
                                        if (callId == "call-input") edit = onChange
                                        original.FreeForm(text, enabled, autoFocus, onChange, onSubmitted)
                                    }
                                }
                            }
                        })
                    }
                }
            }
            UserInputHost(vm, renderer)
        }
        try {
            scene.settle { option != null && edit != null }
            val oldOption = assertNotNull(option)
            val oldEdit = assertNotNull(edit)
            ports.pending.value = inputEvent("replacement", "Replacement prompt")
            scene.settle { vm.pendingInput().callId == "replacement" && scene.hasText("Scope: Replacement prompt") }
            oldOption()
            assertFalse(oldEdit("late text"))
            scene.settle { true }
            assertTrue(vm.pendingInput().answers.isEmpty())
            assertTrue(targets.isEmpty())
            assertTrue(ports.completed.isEmpty())
        } finally { scene.close() }
    } }

    test("Submit callback retains its displayed revision rather than borrowing a newer draft") { userInputScene { ports, vm, _ ->
        var submit: (() -> Unit)? = null
        val scene = OffscreenScene.create {
            val graphical = rememberComposeUserInputRenderer()
            val renderer = object : UserInputRenderer {
                @Composable
                override fun Frame(callId: String, revision: Long, questionCount: Int, content: @Composable (UserInputRenderScope) -> Unit) {
                    graphical.Frame(callId, revision, questionCount) { controls ->
                        content(object : UserInputRenderScope by controls {
                            @Composable override fun Submit(label: String, enabled: Boolean, onClick: () -> Unit) {
                                if (enabled) submit = onClick
                                controls.Submit(label, enabled, onClick)
                            }
                        })
                    }
                }
            }
            UserInputHost(vm, renderer)
        }
        try {
            scene.settle { scene.hasText("Current module") }
            scene.click("Current module")
            scene.setUserAnswer("note", "first")
            scene.settle { vm.pendingInput().canSubmit && submit != null }
            val old = assertNotNull(submit)
            scene.setUserAnswer("note", "newer")
            old()
            scene.settle { vm.pendingInput().answers["note"] == RequestUserInputDraftAnswer.FreeForm("newer") }
            assertTrue(ports.completed.isEmpty())
            assertIs<RequestUserInputSubmissionState.Editing>(vm.pendingInput().submission)
            scene.click("Submit")
            scene.settle { ports.order == listOf("complete", "resume") }
            val response = (ports.completed.single() as StableRequestUserInputToolEvent).result as StableRequestUserInputResult.Answered
            assertEquals(listOf("user_note: newer"), response.response.answers.getValue("note").answers)
        } finally { scene.close() }
    } }

    test("unmount cancels the caller, not an accepted Agent-owned completion or the borrowed child") { userInputScene { ports, vm, owner ->
        ports.gate = CompletableDeferred()
        val visible = mutableStateOf(true)
        val scene = OffscreenScene.create {
            if (visible.value) UserInputHost(vm) else UiText("Unmounted")
        }
        try {
            scene.settle { scene.hasText("Current module") }
            scene.click("Current module")
            scene.setUserAnswer("note", "done")
            scene.settle { vm.pendingInput().canSubmit }
            scene.click("Submit")
            scene.settle { ports.completed.size == 1 && scene.hasText("Submitting…") }
            val frozen = vm.pendingInput()
            assertFalse(vm.selectOption(frozen.callId, "scope", "Whole workspace"))
            assertFalse(vm.updateFreeForm(frozen.callId, "note", "changed"))
            visible.value = false
            scene.settle { scene.hasText("Unmounted") }
            assertEquals(0, ports.cancellations)
            ports.gate!!.complete(Unit)
            scene.settle { ports.order == listOf("complete", "resume") }
            assertEquals(0, ports.cancellations)
            ports.pending.value = inputEvent("next")
            scene.settle { vm.pendingInput().callId == "next" }
            assertTrue(owner.coroutineContext.job.isActive)
        } finally { scene.close() }
    } }

    test("ordinary submission failure renders once and only an explicit retry completes again") { userInputScene { ports, vm, _ ->
        ports.failure = IllegalStateException("receipt missing")
        val scene = OffscreenScene.create { UserInputHost(vm) }
        try {
            scene.settle { scene.hasText("Current module") }
            scene.click("Current module")
            scene.setUserAnswer("note", "retained")
            scene.settle { vm.pendingInput().canSubmit }
            scene.click("Submit")
            scene.settle { scene.hasText("Unable to submit: receipt missing") }
            assertEquals(1, ports.completed.size)
            assertEquals(listOf("complete"), ports.order)
            assertEquals(RequestUserInputDraftAnswer.FreeForm("retained"), vm.pendingInput().answers["note"])
            ports.failure = null
            scene.setUserAnswer("note", "explicit")
            scene.settle { !scene.hasText("Unable to submit: receipt missing") }
            assertEquals(1, ports.completed.size)
            scene.click("Submit")
            scene.settle { scene.hasText("No pending input") }
            assertEquals(2, ports.completed.size)
            assertEquals(listOf("complete", "complete", "resume"), ports.order)
        } finally { scene.close() }
    } }
}

@Composable
private fun UserInputHost(
    vm: RequestUserInputViewModel,
    renderer: UserInputRenderer = rememberComposeUserInputRenderer(),
    targets: MutableList<UserInputFocusTarget>? = null,
) {
    val state = vm.state.collectAsState().value
    if (state is RequestUserInputState.Pending) {
        val tracked = object : UserInputRenderer {
            @Composable override fun Frame(callId: String, revision: Long, questionCount: Int, content: @Composable (UserInputRenderScope) -> Unit) {
                renderer.Frame(callId, revision, questionCount) { controls ->
                    content(object : UserInputRenderScope by controls {
                        override fun requestFocus(target: UserInputFocusTarget) { targets?.add(target); controls.requestFocus(target) }
                    })
                }
            }
        }
        RequestUserInputContent(vm, state, tracked)
    } else UiText("No pending input")
}

private fun OffscreenScene.setUserAnswer(id: String, value: String) {
    val node = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Answer for $id") == true }
    val edit = node.inputNodes().mapNotNull { it.config.getOrNull(SemanticsActions.SetText)?.action }.firstOrNull()
    assertTrue(assertNotNull(edit)(AnnotatedString(value)))
}

private fun OffscreenScene.doneUserAnswer(id: String) {
    val node = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Answer for $id") == true }
    val done = node.inputNodes().mapNotNull { it.config.getOrNull(SemanticsActions.OnImeAction)?.action }.firstOrNull()
    assertTrue(assertNotNull(done)())
}

private fun SemanticsNode.inputNodes(): List<SemanticsNode> = listOf(this) + children.flatMap { it.inputNodes() }

private fun OffscreenScene.hasFocusedInput(value: String): Boolean = nodes().any {
    it.config.getOrNull(SemanticsProperties.Focused) == true &&
        it.config.getOrNull(SemanticsProperties.EditableText)?.text == value
}

private fun RequestUserInputViewModel.pendingInput(): RequestUserInputState.Pending = assertIs(state.value)

private suspend fun userInputScene(block: suspend (InputPorts, RequestUserInputViewModel, CoroutineScope) -> Unit) = offscreenTest {
    val ports = InputPorts()
    val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
    val vm = createRequestUserInputViewModel(ports, owner)
    try { block(ports, vm, owner) } finally { vm.close(); owner.coroutineContext.job.cancelAndJoin() }
}

internal fun inputEvent(callId: String = "call-input", prompt: String = "Which scope?") = PendingRequestUserInputToolEvent(
    callId, arguments = RequestUserInputArgs(listOf(
        RequestUserInputQuestion("scope", "Scope", prompt, options = listOf(
            RequestUserInputQuestionOption("Current module", "Only this module"),
            RequestUserInputQuestionOption("Whole workspace", "All modules"),
        )),
        RequestUserInputQuestion("note", "Note", "Explain"),
    )),
)

internal class InputPorts : RequestUserInputDependencies {
    override val pending = MutableStateFlow<PendingRequestUserInputToolEvent?>(inputEvent())
    val completed = mutableListOf<StableCleanEvent.CompletedTool>()
    val order = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var failure: Throwable? = null
    var cancellations = 0
    override suspend fun completeToolCall(completed: StableCleanEvent.CompletedTool): Int {
        this.completed += completed
        order += "complete"
        try { gate?.await() } catch (failure: CancellationException) { cancellations++; throw failure }
        failure?.let { throw it }
        return 123
    }
    override fun resumeRuntime() { order += "resume" }
}
