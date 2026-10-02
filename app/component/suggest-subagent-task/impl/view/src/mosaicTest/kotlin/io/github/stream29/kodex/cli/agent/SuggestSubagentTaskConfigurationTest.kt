package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.SuggestedSessionConfiguration
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskSubmissionResult
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskViewModel
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val suggestSubagentTaskConfigurationTest by testSuite {
    test("configured model survives missing catalog and all configuration labels remain visible") {
        val configuration = rendererConfiguration().copy(serviceTier = ServiceTier.Fast)
        assertEquals(listOf(OpenAiModelId("catalog"), configuration.model),
            suggestionModelOptions(listOf(ModelInfo(OpenAiModelId("catalog"), "Catalog")), configuration))
        assertEquals("test low fast", suggestionConfigurationLabel(configuration))
        assertEquals("cwd", suggestionWorkingDirectoryLabel(Path("/work"), 64))
        assertEquals("/work", suggestionWorkingDirectoryLabel(Path("/work"), 72))
        assertTrue(suggestionWorkingDirectoryLabel(Path("/very/long/path/to/the/workspace"), 72)
            .startsWith("…"))
        val model = ConfigurationRendererModel(configuration)
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                SuggestSubagentTaskPanel(
                    model, model.state.value as SuggestSubagentTaskState.Pending,
                    90, 12, SuggestSubagentTaskDropdowns.remember(model, "suggestion"), {},
                )
            }
            assertTrue("[test low fast]" in snapshot, snapshot)
            assertTrue("[ask user]" in snapshot, snapshot)
            assertTrue("[/work]" in snapshot, snapshot)
        }
    }

    test("question-mode menu edits exact component call and cwd browse carries call identity") {
        val model = ConfigurationRendererModel(rendererConfiguration())
        var browsed: String? = null
        lateinit var dropdowns: SuggestSubagentTaskDropdowns
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                val pending by model.state.collectAsState()
                dropdowns = SuggestSubagentTaskDropdowns.remember(model, "suggestion")
                TuiPopupHost(Modifier.width(90).height(18)) {
                    SuggestSubagentTaskPanel(model, pending as SuggestSubagentTaskState.Pending,
                        90, 12, dropdowns, { browsed = it })
                    SuggestSubagentTaskConfigurationMenus(
                        model, pending as SuggestSubagentTaskState.Pending, dropdowns,
                    )
                }
            }
            val row = snapshot.lines().indexOfFirst { "[/work]" in it }
            assertTrue(row >= 0, snapshot)
            val column = snapshot.lines()[row].indexOf("[/work]") + 1
            sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Release))
            awaitSnapshot()
            assertEquals("suggestion", browsed)
            dropdowns.requestUserInputMode.expand()
            val menu = awaitSnapshot()
            assertTrue("no question" in menu, menu)
            sendKeyEvent(KeyboardEvent(codepoint = 57353))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
            assertEquals(listOf("suggestion" to RequestUserInputMode.NoQuestion), model.modeEdits)
            assertFalse(dropdowns.requestUserInputMode.expanded)
        }
    }

    test("model effort tier submenu delegates one explicit triple command") {
        val model = ConfigurationRendererModel(rendererConfiguration())
        lateinit var dropdowns: SuggestSubagentTaskDropdowns
        runMosaicTest {
            setContentAndSnapshot {
                dropdowns = SuggestSubagentTaskDropdowns.remember(model, "suggestion")
                TuiPopupHost(Modifier.width(90).height(18)) {
                    val pending = model.state.value as SuggestSubagentTaskState.Pending
                    SuggestSubagentTaskPanel(model, pending, 90, 12, dropdowns, {})
                    SuggestSubagentTaskConfigurationMenus(model, pending, dropdowns)
                }
            }
            dropdowns.model.expand()
            assertTrue("test" in awaitSnapshot())
            sendKeyEvent(KeyboardEvent(codepoint = 57351))
            assertTrue("low" in awaitSnapshot())
            sendKeyEvent(KeyboardEvent(codepoint = 57351))
            assertTrue("default" in awaitSnapshot())
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
            assertEquals(listOf("suggestion" to rendererConfiguration()), model.modelEdits)
        }
    }

    test("submitting disables configuration triggers and disposes an already open menu") {
        val model = ConfigurationRendererModel(rendererConfiguration())
        lateinit var dropdowns: SuggestSubagentTaskDropdowns
        runMosaicTest {
            setContentAndSnapshot {
                val current by model.state.collectAsState()
                dropdowns = SuggestSubagentTaskDropdowns.remember(model, "suggestion")
                TuiPopupHost(Modifier.width(90).height(18)) {
                    SuggestSubagentTaskPanel(model, current as SuggestSubagentTaskState.Pending,
                        90, 12, dropdowns, {})
                    SuggestSubagentTaskConfigurationMenus(
                        model, current as SuggestSubagentTaskState.Pending, dropdowns,
                    )
                }
            }
            dropdowns.requestUserInputMode.expand()
            awaitSnapshot()
            model.state.value = (model.state.value as SuggestSubagentTaskState.Pending)
                .copy(submitting = true)
            awaitSnapshot()
            // Trigger dismissal is a LaunchedEffect, not part of the render snapshot itself.
            snapshotFlow { dropdowns.requestUserInputMode.expanded }.first { !it }
            assertFalse(dropdowns.requestUserInputMode.expanded)
            assertTrue(model.modeEdits.isEmpty())
            assertTrue(model.modelEdits.isEmpty())
        }
    }
}

private fun rendererConfiguration(): SuggestedSessionConfiguration = SuggestedSessionConfiguration(
    OpenAiModelId("test"), ReasoningEffort.Low, ServiceTier.Default,
    Path("/work"), RequestUserInputMode.AskUser,
)

private class ConfigurationRendererModel(configuration: SuggestedSessionConfiguration) :
    SuggestSubagentTaskViewModel {
    override val state = MutableStateFlow<SuggestSubagentTaskState>(SuggestSubagentTaskState.Pending(
        "suggestion", SuggestSubagentTaskArgs(listOf(SuggestedSubagentTask("Task", "Work"))),
        configuration,
    ))
    override val models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val modeEdits = mutableListOf<Pair<String, RequestUserInputMode>>()
    val modelEdits = mutableListOf<Pair<String, SuggestedSessionConfiguration>>()
    override fun updateModelConfiguration(
        callId: String, model: OpenAiModelId, reasoningEffort: ReasoningEffort, serviceTier: ServiceTier,
    ): Boolean {
        val current = state.value as SuggestSubagentTaskState.Pending
        modelEdits += callId to current.configuration.copy(
            model = model, reasoningEffort = reasoningEffort, serviceTier = serviceTier,
        )
        return true
    }
    override fun updateRequestUserInputMode(callId: String, mode: RequestUserInputMode): Boolean {
        modeEdits += callId to mode
        return true
    }
    override fun updateFeedback(callId: String, text: String) = false
    override fun setRejecting(callId: String, rejecting: Boolean) = false
    override fun updateConfiguration(callId: String, configuration: SuggestedSessionConfiguration) = false
    override fun updateWorkingDirectory(callId: String, directory: Path) = false
    override suspend fun submit(callId: String, expectedRevision: Long, accepted: Boolean) =
        SuggestSubagentTaskSubmissionResult.Stale
    override fun close() = Unit
}
