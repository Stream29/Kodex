package io.github.stream29.kodex.cli.app

import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Spacer
import io.github.stream29.kodex.app.agent.contract.SuggestedSessionConfiguration
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskViewModel
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskSubmissionResult
import io.github.stream29.kodex.cli.agent.SuggestSubagentTaskPanel
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuggestedConfigurationTest {
    private val configuration = RuntimeConfiguration(
        OpenAiModelId("test"), ReasoningEffort.Low, ServiceTier.Default, RequestUserInputMode.AskUser,
    )

    @Test
    fun cwdSharesTheConfigurationRowWhenItFits() = verifyWidth(80, sameRow = true)

    @Test
    fun cwdWrapsOnlyWhenTheRowIsFull() = verifyWidth(22, sameRow = false)

    private fun verifyWidth(columns: Int, sameRow: Boolean) = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                SuggestedConfigurationTriggers(
                    columns, configuration, Path("."), RuntimeConfigurationDropdowns.remember(Unit),
                    enabled = true, onBrowse = {},
                )
            }
            val lines = snapshot.lines()
            val modelRow = lines.indexOfFirst { "[test low]" in it }
            val cwdLabel = "[${workingDirectoryStatusLabel(Path("."), columns)}]"
            val cwdRow = lines.indexOfFirst { cwdLabel in it }
            assertTrue(modelRow >= 0 && cwdRow >= 0, snapshot)
            assertEquals(sameRow, modelRow == cwdRow, snapshot)
        }
    }

    @Test
    fun scrolledPanelMenuUsesTheTriggerSurfaceCoordinates() = runTest {
        val pending = SuggestSubagentTaskState.Pending(
            callId = "offset-panel",
            arguments = SuggestSubagentTaskArgs(
                listOf(SuggestedSubagentTask("Task", "Long instructions\n".repeat(30))),
            ),
            configuration = SuggestedSessionConfiguration(
                configuration.model, configuration.reasoning, configuration.tier,
                Path("."), configuration.requestUserInputMode,
            ),
        )
        val viewModel = object : SuggestSubagentTaskViewModel {
            override val state = MutableStateFlow<SuggestSubagentTaskState>(pending)
            override fun updateFeedback(callId: String, text: String) = false
            override fun updateConfiguration(callId: String, configuration: SuggestedSessionConfiguration) = false
            override suspend fun submit(
                callId: String, expectedRevision: Long, accepted: Boolean,
            ): SuggestSubagentTaskSubmissionResult = error("Not used")
            override fun close() = Unit
        }
        runMosaicTest {
            setContentAndSnapshot {
                val dropdowns = RuntimeConfigurationDropdowns.remember(Unit)
                TuiPopupHost(Modifier.width(90).height(25)) {
                    Row {
                        Spacer(Modifier.width(7))
                        Column {
                            Spacer(Modifier.height(4))
                            SuggestSubagentTaskPanel(viewModel, pending, columns = 70, rows = 12) {
                                SuggestedConfigurationTriggers(
                                    70, configuration, Path("."), dropdowns, enabled = true, onBrowse = {},
                                )
                            }
                        }
                    }
                    RuntimeConfigurationMenus(
                        configuration, emptyList(), listOf(configuration.model), dropdowns,
                        onConfigurationSelected = { _, _, _ -> },
                        onRequestUserInputModeSelected = {},
                    )
                }
            }
            repeat(50) {
                sendMouseEvent(MouseEvent(9, 6, MouseEvent.Type.Press, MouseEvent.Button.WheelDown))
            }
            val scrolled = awaitSnapshot()
            val lines = scrolled.lines()
            val triggerY = lines.indexOfFirst { "[ask user]" in it }
            assertTrue(triggerY >= 4, scrolled)
            val triggerX = lines[triggerY].indexOf("[ask user]")
            assertTrue(triggerX >= 7, scrolled)
            sendMouseEvent(MouseEvent(triggerX + 1, triggerY, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(triggerX + 1, triggerY, MouseEvent.Type.Release, MouseEvent.Button.Left))
            var menu = awaitSnapshot()
            repeat(3) {
                if ("[no question]" !in menu) menu = awaitSnapshot()
            }
            val menuLines = menu.lines()
            val menuY = menuLines.indexOfFirst { "[no question]" in it }
            assertEquals(triggerY - 1, menuY, menu)
            assertEquals(triggerX, menuLines[menuY].indexOf("[no question]"), menu)
        }
    }
}
