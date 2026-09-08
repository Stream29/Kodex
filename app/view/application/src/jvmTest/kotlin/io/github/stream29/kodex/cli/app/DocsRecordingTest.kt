package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.Mosaic
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem
import io.github.stream29.kodex.cli.agent.RequestUserInputPanel
import io.github.stream29.kodex.cli.agent.SuggestSubagentTaskPanel
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.cli.components.rememberScrollState
import io.github.stream29.kodex.cli.components.verticalScroll
import io.github.stream29.kodex.cli.history.render
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.kodex.utils.applypatch.Patch
import io.github.stream29.kodex.utils.applypatch.UpdateFileHunk
import io.github.stream29.kodex.utils.applypatch.UpdateFileChunk
import io.github.stream29.kodex.utils.applypatch.AddFileHunk
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.requestuserinput.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.io.files.Path
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Opt-in docs exports of production Compose renderers, never hand-drawn ANSI.
 * Set KODEX_DOCS_RECORDINGS_DIR to export casts + plain frame assertions.
 * Timing is editorial (two seconds per checkpoint); data is an offline fixture.
 */
val docsRecordingTest by testSuite {
    test("large patch reveals the next page through the actual control") {
        val patch = Patch("", listOf(AddFileHunk(
            path = "long-example.txt",
            contents = (1..405).joinToString("\n", postfix = "\n") { "Example line $it" },
        )))
        val clip = DocsClip("patch-pages")
        runMosaicTest(MosaicSnapshots) {
            setContentAndSnapshot {
                Column(Modifier.width(80).height(18).verticalScroll(rememberScrollState())) {
                    PendingPatchToolEvent(callId = "docs-long-patch", diff = patch).render()
                }
            }
            clip.add(settle(), "long-example.txt")
            clickLabel("long-example.txt")
            clickLabel("Changes")
            clip.add(settle(), "Example line 1")
            repeat(80) { sendMouseEvent(MouseEvent(5, 10, MouseEvent.Type.Press, MouseEvent.Button.WheelDown)) }
            clip.add(settle(), "Show next 200 lines")
            clickLabel("Show next 200 lines")
            repeat(80) { sendMouseEvent(MouseEvent(5, 10, MouseEvent.Type.Press, MouseEvent.Button.WheelDown)) }
            clip.add(settle(), "Show next 6 lines")
            clickLabel("Show next 6 lines")
            repeat(10) { sendMouseEvent(MouseEvent(5, 10, MouseEvent.Type.Press, MouseEvent.Button.WheelDown)) }
            clip.add(settle(), "Example line 405")
        }
        clip.save()
    }

    test("pending patch expands a real structured diff without executing it") {
        val patch = Patch("", listOf(UpdateFileHunk(
            path = "README.md",
            chunks = listOf(UpdateFileChunk(
                oldLines = listOf("Start a session."),
                newLines = listOf("Start a session in your project directory."),
            )),
        )))
        val clip = DocsClip("patch")
        runMosaicTest(MosaicSnapshots) {
            setContentAndSnapshot {
                Column(Modifier.width(80).height(18)) { PendingPatchToolEvent(callId = "docs-patch", diff = patch).render() }
            }
            clip.add(settle(), "Editing README.md")
            clickLabel("Editing")
            clip.add(settle(), "Changes")
            clickLabel("Changes")
            clip.add(settle(), "+ Start a session in your project directory.")
        }
        clip.save()
    }

    test("question form receives real mouse and keyboard input") {
        val model = DocsQuestions()
        val clip = DocsClip("questions")
        runMosaicTest(MosaicSnapshots) {
            setContentAndSnapshot {
                val state by model.state.collectAsState()
                RequestUserInputPanel(model, state as RequestUserInputState.Pending, 80, 18)
            }
            clip.add(settle(), "Other")
            clickLabel("Other")
            clip.add(settle(), "  >")
            for (char in "Keep public APIs") sendKeyEvent(KeyboardEvent(codepoint = char.code))
            clip.add(settle(), "Keep public APIs")
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            clip.add(settle(), "Tests")
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            clip.add(settle(), "Submit")
            clickLabel("Other")
            clip.add(settle(), "Keep public APIs")
            for (char in " only") sendKeyEvent(KeyboardEvent(codepoint = char.code))
            clip.add(settle(), "Keep public APIs only")
            assertTrue(!model.submitted)
            clickLabel("[Submit]")
            settle()
            assertTrue(model.submitted, "Selections must still require explicit Submit")
        }
        clip.save()
    }

    test("suggestion rejection uses the production approval panel") {
        val initial = SuggestSubagentTaskState.Pending(
            callId = "docs-suggestions",
            arguments = SuggestSubagentTaskArgs(listOf(
                SuggestedSubagentTask("Review tests", "Review the changed tests; do not edit files."),
                SuggestedSubagentTask("Review docs", "Check examples against the implementation."),
            )),
            configuration = SuggestedSessionConfiguration(
                OpenAiModelId("demo-model"), ReasoningEffort.Medium, ServiceTier.Default,
                Path("/work/demo"), RequestUserInputMode.AskUser,
            ),
        )
        var decision: Boolean? = null
        val model = object : SuggestSubagentTaskViewModel {
            override val state = MutableStateFlow<SuggestSubagentTaskState>(initial)
            override fun updateFeedback(callId: String, text: String): Boolean {
                val current = state.value as SuggestSubagentTaskState.Pending
                state.value = current.copy(feedback = text, revision = current.revision + 1)
                return true
            }
            override fun updateConfiguration(callId: String, configuration: SuggestedSessionConfiguration): Boolean {
                val current = state.value as SuggestSubagentTaskState.Pending
                state.value = current.copy(configuration = configuration, revision = current.revision + 1)
                return true
            }
            override suspend fun submit(callId: String, expectedRevision: Long, accepted: Boolean): SuggestSubagentTaskSubmissionResult {
                assertEquals((state.value as SuggestSubagentTaskState.Pending).revision, expectedRevision)
                decision = accepted
                return SuggestSubagentTaskSubmissionResult.Submitted
            }
            override fun close() = Unit
        }
        val clip = DocsClip("suggestions")
        runMosaicTest(MosaicSnapshots) {
            setContentAndSnapshot {
                val state by model.state.collectAsState()
                val pending = state as SuggestSubagentTaskState.Pending
                val c = pending.configuration
                val configuration = RuntimeConfiguration(c.model, c.reasoningEffort, c.serviceTier, c.requestUserInputMode)
                val dropdowns = RuntimeConfigurationDropdowns.remember(model)
                TuiPopupHost(Modifier.width(80).height(18)) {
                    SuggestSubagentTaskPanel(model, pending, 80, 18) {
                        SuggestedConfigurationTriggers(80, configuration, c.cwd, dropdowns, true, {})
                    }
                    RuntimeConfigurationMenus(
                        configuration, listOf(ModelInfo(
                            c.model, "Offline model fixture",
                            supportedReasoningLevels = listOf(
                                ReasoningEffortPreset(ReasoningEffort.Medium, "Medium"),
                                ReasoningEffortPreset(ReasoningEffort.High, "High"),
                            ),
                        )), listOf(c.model), dropdowns,
                        onConfigurationSelected = { m, r, t ->
                            model.updateConfiguration(pending.callId, c.copy(model = m, reasoningEffort = r, serviceTier = t))
                        },
                        onRequestUserInputModeSelected = { mode ->
                            model.updateConfiguration(pending.callId, c.copy(requestUserInputMode = mode))
                        },
                    )
                }
            }
            clip.add(settle(), "Review tests")
            clickLabel("[ask user]")
            clip.add(settle(), "no question")
            clickLabel("no question")
            clip.add(settle(), "[no question]")
            clickLabel("[demo-model medium]")
            clip.add(settle(), "demo-model")
            clickLabel("[demo-model ")
            clip.add(settle(), "high")
            clickLabel("[high")
            clip.add(settle(), "default")
            clickLabel("[default")
            clip.add(settle(), "demo-model high")
            clickLabel("Reject")
            clip.add(settle(), "Submit rejection")
            for (char in "Keep this review in one session") sendKeyEvent(KeyboardEvent(codepoint = char.code))
            clip.add(settle(), "Keep this review")
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            settle()
            assertEquals(false, decision)
        }
        clip.save()
    }

    test("runtime screen renders running steer and distinct idle controls") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val real = fixture.persistedSession("Documentation example").rootAgent
            val running = MutableStateFlow(AgentExecutionState(
                running = true, capabilities = AgentExecutionCapabilities(canCancel = true),
            ))
            val steer = MutableStateFlow<List<StableIndexEvent.Steerable>>(emptyList())
            val streaming = MutableStateFlow<HistoryStreamingItem?>(null)
            var resumed = false
            var compacted = false
            val model = object : AgentViewModel by real {
                override val execution = running
                override val pendingSteer = steer
                override val history = object : AgentHistoryViewModel by real.history {
                    override val streamingItem = streaming
                }
                // No model calls: these are input states for the real screen.
                override fun cancel() {
                    streaming.value = null
                    running.value = AgentExecutionState(
                        capabilities = AgentExecutionCapabilities(canResume = true, canCompact = true),
                    )
                }
                override fun resume() {
                    resumed = true
                    running.value = AgentExecutionState(running = true, phase = AgentExecutionPhase.Responding,
                        capabilities = AgentExecutionCapabilities(canCancel = true))
                    streaming.value = HistoryStreamingItem.Started
                }
                override fun forceCompact() {
                    compacted = true
                    running.value = AgentExecutionState(running = true, phase = AgentExecutionPhase.Compacting,
                        capabilities = AgentExecutionCapabilities(canCancel = true))
                    streaming.value = HistoryStreamingItem.Compacting
                }
                override fun clearPending() { cancel() }
                override suspend fun submitComposer(expectedRevision: Long): AgentComposerSubmissionResult {
                    val text = composer.state.value.text
                    check(composer.clear(expectedRevision))
                    steer.value = steer.value + StableUserMessage(listOf(ContentItem.InputText(text)))
                    return AgentComposerSubmissionResult.QueuedAsSteer
                }
            }
            val clip = DocsClip("execution")
            runMosaicTest(MosaicSnapshots) {
                setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(80).height(18)) {
                        AgentRuntimeScreen(
                            model, 80, 18, NewLineKey.ShiftEnter,
                            RuntimeConfigurationDropdowns.remember(model),
                            RuntimeConfigurationDropdowns.remember("suggestions"),
                            { _, _, _, _ -> }, {}, {}, {},
                        )
                    }
                }
                clip.add(settle(), "Stop")
                for (char in "Keep the public API unchanged.") sendKeyEvent(KeyboardEvent(codepoint = char.code))
                clip.add(settle(), "Keep the public API unchanged.")
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                clip.add(settle(), "Pending steer")
                clickLabel("Stop")
                clip.add(settle(), "Resume")
                running.value = AgentExecutionState(capabilities = AgentExecutionCapabilities(canClearPending = true))
                clip.add(settle(), "Clear pending")
                clickLabel("Clear pending")
                clip.add(settle(), "Compact")
                assertTrue(steer.value.isNotEmpty(), "Clearing tools must not be illustrated as clearing steer")
                clickLabel("Resume")
                clip.add(settle(), "Stop")
                assertTrue(resumed)
                clickLabel("Stop")
                clickLabel("Compact")
                clip.add(settle(), "Stop")
                assertTrue(compacted)
                clickLabel("Stop")
                clip.add(settle(), "Resume")
            }
            clip.save()
        } finally {
            fixture.close()
        }
    }

    test("history tool details and real entry menu") {
        val tool = StableTextToolEvent(
            callId = "docs-example", name = "example_tool",
            arguments = JsonObject(mapOf("path" to JsonPrimitive("README.md"))),
            result = "Example data — not an executed tool.", success = true,
        )
        val plan = StablePlanUpdate(callId = "docs-plan", arguments = UpdatePlanArgs(plan = listOf(
            PlanItemArg("Read project instructions", StepStatus.Completed),
            PlanItemArg("Review the changes", StepStatus.InProgress),
            PlanItemArg("Run relevant checks", StepStatus.Pending),
        )))
        var menu by mutableStateOf(false)
        var selected: String? = null
        val clip = DocsClip("history")
        runMosaicTest(MosaicSnapshots) {
            setContentAndSnapshot {
                val anchor = rememberTuiPopupAnchor()
                TuiPopupHost(Modifier.width(80).height(18)) {
                    Column(Modifier.width(80).height(18).tuiPopupAnchor(anchor)) {
                        tool.render()
                        plan.render()
                    }
                    if (menu) HistoryEntryContextMenuPopup(
                        anchor, null, { menu = false },
                        { selected = "revert"; menu = false },
                        { selected = "fork"; menu = false },
                    )
                }
            }
            clip.add(settle(), "example_tool")
            clickLabel("example_tool")
            clip.add(settle(), "Arguments")
            clickLabel("Arguments")
            clip.add(settle(), "README.md")
            clickLabel("Result")
            clip.add(settle(), "Example data")
            menu = true
            clip.add(settle(), "Fork from here")
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
            clip.add(settle(), "Fork from here")
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            settle()
            assertEquals("fork", selected)
        }
        clip.save()
    }
}

private class DocsQuestions : RequestUserInputViewModel {
    override val state = MutableStateFlow<RequestUserInputState>(RequestUserInputState.Pending(
        callId = "docs-questions",
        arguments = RequestUserInputArgs(listOf(
            RequestUserInputQuestion("scope", "Scope", "What should the review preserve?", options = listOf(
                RequestUserInputQuestionOption("Public API", "Keep existing callers working."),
            )),
            RequestUserInputQuestion("tests", "Tests", "Which checks should run?", options = listOf(
                RequestUserInputQuestionOption("Focused tests", "Run the relevant test suite."),
            )),
        )),
    ))
    var submitted = false
    private fun answer(id: String, answer: RequestUserInputDraftAnswer): Boolean {
        val current = state.value as RequestUserInputState.Pending
        state.value = current.copy(answers = current.answers + (id to answer), revision = current.revision + 1)
        return true
    }
    override fun selectOption(callId: String, questionId: String, label: String) =
        answer(questionId, RequestUserInputDraftAnswer.Option(label))
    override fun selectOther(callId: String, questionId: String): Boolean {
        val previous = (state.value as RequestUserInputState.Pending).answers[questionId]
        return answer(questionId, previous as? RequestUserInputDraftAnswer.FreeForm ?: RequestUserInputDraftAnswer.FreeForm(""))
    }
    override fun updateFreeForm(callId: String, questionId: String, text: String) =
        answer(questionId, RequestUserInputDraftAnswer.FreeForm(text))
    override suspend fun submit(callId: String, expectedRevision: Long): RequestUserInputSubmissionResult {
        val current = state.value as RequestUserInputState.Pending
        assertEquals(current.revision, expectedRevision)
        assertTrue(current.canSubmit)
        submitted = true
        return RequestUserInputSubmissionResult.Submitted
    }
    override fun close() = Unit
}

internal suspend fun TestMosaic<Mosaic>.settle(): Mosaic {
    repeat(3) {
        try { awaitSnapshot(100.milliseconds) } catch (_: TimeoutCancellationException) { }
    }
    return this
}

internal suspend fun TestMosaic<Mosaic>.clickLabel(label: String, button: MouseEvent.Button = MouseEvent.Button.Left) {
    val lines = draw().render(AnsiLevel.NONE, false).lines()
    val row = lines.indexOfFirst { label in it }
    check(row >= 0) { "Missing $label in ${lines.joinToString("\n")}" }
    val column = lines[row].indexOf(label) + 1
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Press, button))
    settle()
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Release, button))
    settle()
}

internal class DocsClip(private val name: String, private val width: Int = 80, private val height: Int = 18) {
    private val frames = mutableListOf<Pair<String, String>>()
    fun add(mosaic: Mosaic, expected: String) {
        val draw = mosaic.draw()
        val plain = draw.render(AnsiLevel.NONE, false)
        assertTrue(expected in plain, "Expected $expected in:\n$plain")
        frames += plain to draw.render(AnsiLevel.TRUECOLOR, false)
    }
    fun save() {
        val dir = System.getenv("KODEX_DOCS_RECORDINGS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        File(dir, "$name.cast").writeText(buildString {
            appendLine("""{"version":2,"width":$width,"height":$height,"title":"Kodex production components — offline fixture: $name"}""")
            frames.forEachIndexed { index, (_, ansi) ->
                val output = "\u001b[?25l\u001b[0m\u001b[2J\u001b[H" + ansi.replace("\n", "\r\n")
                appendLine("[${index * 2.0},\"o\",${JsonPrimitive(output)}]")
            }
            appendLine("[${frames.size * 2.0},\"o\",\"\"]")
        })
        frames.forEachIndexed { index, (plain, _) -> File(dir, "$name-$index.txt").writeText(plain) }
    }
}
