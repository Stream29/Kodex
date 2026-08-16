package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.AgentExecutionState
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.AgentMode
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ModelServiceTier
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ReasoningEffortPreset
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeStatusBarTest {
    @Test
    fun agentModesUseExplicitLabels() {
        assertEquals("single agent", AgentMode.Single.displayName())
        assertEquals("multi agent", AgentMode.Multi.displayName())
    }

    @Test
    fun requestUserInputModesUseExplicitLabels() {
        assertEquals("ask user", RequestUserInputMode.AskUser.displayName())
        assertEquals("no question", RequestUserInputMode.NoQuestion.displayName())
    }

    @Test
    fun combinedConfigurationLabelOmitsOnlyTheDefaultTier() {
        val model = OpenAiModelId("gpt-5.6-sol")

        assertEquals(
            "gpt-5.6-sol max",
            runtimeConfigurationLabel(model, ReasoningEffort.Max, ServiceTier.Default),
        )
        assertEquals(
            "gpt-5.6-sol max fast",
            runtimeConfigurationLabel(model, ReasoningEffort.Max, ServiceTier.Fast),
        )
    }

    @Test
    fun workingDirectoryLabelPreservesThePathTailAndCollapsesOnNarrowSurfaces() {
        val workingDirectory = Path("root", "projects", "very-long-project-directory", "workspace")

        assertEquals("cwd", workingDirectoryStatusLabel(workingDirectory, columns = 60))

        val regular = workingDirectoryStatusLabel(workingDirectory, columns = 80)
        assertTrue(regular.startsWith("…"), regular)
        assertTrue(regular.endsWith("workspace"), regular)
        assertTrue(regular.terminalCellWidth() <= 16, regular)

        val wide = workingDirectoryStatusLabel(workingDirectory, columns = 120)
        assertTrue(wide.startsWith("…"), wide)
        assertTrue(wide.endsWith("workspace"), wide)
        assertTrue(wide.terminalCellWidth() <= 28, wide)
    }

    @Test
    fun runningAgentHidesOnlyCompact() {
        assertTrue(compactVisible(AgentExecutionState(running = false)))
        assertFalse(compactVisible(AgentExecutionState(running = true)))
    }

    @Test
    fun modelMenuSelectsModelReasoningAndTierAcrossThreeLevels() = runTest {
        val model = OpenAiModelId("gpt-5.6-sol")
        val modelInfo = ModelInfo(
            slug = model,
            displayName = "GPT-5.6-Sol",
            supportedReasoningLevels = listOf(
                ReasoningEffortPreset(ReasoningEffort.Max, "Maximum"),
            ),
            serviceTiers = listOf(
                ModelServiceTier(
                    id = ServiceTier.Fast.requestValue,
                    name = "Fast",
                    description = "Priority processing",
                ),
            ),
        )
        var configuration by mutableStateOf(
            RuntimeConfiguration(
                model = model,
                reasoning = ReasoningEffort.Max,
                tier = ServiceTier.Default,
                agentMode = AgentMode.Single,
                requestUserInputMode = RequestUserInputMode.AskUser,
            ),
        )
        lateinit var dropdowns: RuntimeConfigurationDropdowns

        runMosaicTest {
            val initial = setContentAndSnapshot {
                dropdowns = RuntimeConfigurationDropdowns.remember(owner = Unit)
                TuiPopupHost(modifier = Modifier.width(60).height(12)) {
                    Row {
                        RuntimeConfigurationTriggers(configuration, dropdowns)
                    }
                    RuntimeConfigurationMenus(
                        configuration = configuration,
                        models = listOf(modelInfo),
                        modelOptions = listOf(model),
                        dropdowns = dropdowns,
                        onConfigurationSelected = { selectedModel, effort, tier ->
                            configuration = configuration.copy(
                                model = selectedModel,
                                reasoning = effort,
                                tier = tier,
                            )
                        },
                        onAgentModeSelected = { agentMode ->
                            configuration = configuration.copy(agentMode = agentMode)
                        },
                        onRequestUserInputModeSelected = { mode ->
                            configuration = configuration.copy(requestUserInputMode = mode)
                        },
                    )
                }
            }
            assertTrue("[gpt-5.6-sol max]" in initial, initial)
            assertTrue("[single agent]" in initial, initial)

            val modelButtonStart = initial.indexOf("[gpt-5.6-sol max]")
            assertTrue(modelButtonStart >= 0, initial)
            click(modelButtonStart + 1)
            awaitSnapshotContaining("gpt-5.6-sol")
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
            awaitSnapshotContaining("[max >]")
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
            val tierMenu = awaitSnapshotContaining("[default]")
            assertTrue("fast" in tierMenu, tierMenu)

            sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshotContaining("[gpt-5.6-sol max fast]")
        }

        assertEquals(ServiceTier.Fast, configuration.tier)
    }

    @Test
    fun agentModeTriggerOpensAndSelectsMultiAgent() = runTest {
        val model = OpenAiModelId("test-model")
        var configuration by mutableStateOf(
            RuntimeConfiguration(
                model = model,
                reasoning = ReasoningEffort.High,
                tier = ServiceTier.Default,
                agentMode = AgentMode.Single,
                requestUserInputMode = RequestUserInputMode.AskUser,
            ),
        )

        runMosaicTest {
            val initial = setContentAndSnapshot {
                val dropdowns = RuntimeConfigurationDropdowns.remember(owner = Unit)
                TuiPopupHost(modifier = Modifier.width(48).height(8)) {
                    Row {
                        RuntimeConfigurationTriggers(configuration, dropdowns)
                    }
                    RuntimeConfigurationMenus(
                        configuration = configuration,
                        models = emptyList(),
                        modelOptions = listOf(model),
                        dropdowns = dropdowns,
                        onConfigurationSelected = { selectedModel, effort, tier ->
                            configuration = configuration.copy(
                                model = selectedModel,
                                reasoning = effort,
                                tier = tier,
                            )
                        },
                        onAgentModeSelected = { agentMode ->
                            configuration = configuration.copy(agentMode = agentMode)
                        },
                        onRequestUserInputModeSelected = { mode ->
                            configuration = configuration.copy(requestUserInputMode = mode)
                        },
                    )
                }
            }
            val modeButtonStart = initial.indexOf("[single agent]")
            assertTrue(modeButtonStart >= 0, initial)

            click(modeButtonStart + 1)
            val menu = awaitSnapshotContaining("multi agent")
            assertTrue("[single agent]" in menu, menu)
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshotContaining("[multi agent]")
        }

        assertEquals(AgentMode.Multi, configuration.agentMode)
    }

    @Test
    fun questionModeTriggerOpensAndSelectsNoQuestion() = runTest {
        val model = OpenAiModelId("test-model")
        var configuration by mutableStateOf(
            RuntimeConfiguration(
                model = model,
                reasoning = ReasoningEffort.High,
                tier = ServiceTier.Default,
                agentMode = AgentMode.Single,
                requestUserInputMode = RequestUserInputMode.AskUser,
            ),
        )

        runMosaicTest {
            val initial = setContentAndSnapshot {
                val dropdowns = RuntimeConfigurationDropdowns.remember(owner = Unit)
                TuiPopupHost(modifier = Modifier.width(60).height(8)) {
                    Row {
                        RuntimeConfigurationTriggers(configuration, dropdowns)
                    }
                    RuntimeConfigurationMenus(
                        configuration = configuration,
                        models = emptyList(),
                        modelOptions = listOf(model),
                        dropdowns = dropdowns,
                        onConfigurationSelected = { selectedModel, effort, tier ->
                            configuration = configuration.copy(
                                model = selectedModel,
                                reasoning = effort,
                                tier = tier,
                            )
                        },
                        onAgentModeSelected = { agentMode ->
                            configuration = configuration.copy(agentMode = agentMode)
                        },
                        onRequestUserInputModeSelected = { mode ->
                            configuration = configuration.copy(requestUserInputMode = mode)
                        },
                    )
                }
            }
            val modeButtonStart = initial.indexOf("[ask user]")
            assertTrue(modeButtonStart >= 0, initial)

            click(modeButtonStart + 1)
            val menu = awaitSnapshotContaining("no question")
            assertTrue("[ask user]" in menu, menu)
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshotContaining("[no question]")
        }

        assertEquals(RequestUserInputMode.NoQuestion, configuration.requestUserInputMode)
    }

    @Test
    fun newSessionSettingsButtonIsSeparatedAtRightEdge() = runTest {
        val columns = 80

        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                NewSessionStatusBar(
                    columns = columns,
                    settings = testSettings(Path(".")),
                    dropdowns = RuntimeConfigurationDropdowns.remember(owner = Unit),
                    onBrowseWorkingDirectory = {},
                    onOpenSettings = {},
                )
            }

            assertEquals(columns - 1, snapshot.length, snapshot)
            assertTrue(snapshot.endsWith("[Settings]"), snapshot)
            assertTrue(snapshot.dropLast("[Settings]".length).endsWith("  "), snapshot)
        }
    }

    @Test
    fun statusBarPlanPinsSettingsAndWrapsWholeControls() {
        val width = 39
        val itemWidths = listOf(17, 10, 14, 5)

        val plan = statusBarLayoutPlan(
            width = width,
            itemWidths = itemWidths,
            settingsWidth = 10,
        )

        assertEquals(IntOffset(x = 29, y = 0), plan.settingsPosition)
        assertEquals(
            listOf(
                IntOffset(x = 0, y = 0),
                IntOffset(x = 18, y = 0),
                IntOffset(x = 0, y = 1),
                IntOffset(x = 15, y = 1),
            ),
            plan.itemPositions,
        )
        assertEquals(2, plan.rowCount)
        plan.itemPositions.zip(itemWidths).forEach { (position, itemWidth) ->
            if (position.y == 0) {
                assertTrue(position.x + itemWidth < plan.settingsPosition.x)
            } else {
                assertTrue(position.x + itemWidth <= width)
            }
        }
    }

    @Test
    fun newSessionControlsStayCompleteAcrossSupportedWidths() = runTest {
        listOf(40, 60, 80, 120).forEach { columns ->
            runMosaicTest {
                val settings = testSettings(Path("."))
                val snapshot = setContentAndSnapshot {
                    NewSessionStatusBar(
                        columns = columns,
                        settings = settings,
                        dropdowns = RuntimeConfigurationDropdowns.remember(owner = columns),
                        onBrowseWorkingDirectory = {},
                        onOpenSettings = {},
                    )
                }
                val lines = snapshot.lines()

                assertTrue(lines.first().endsWith("[Settings]"), snapshot)
                assertTrue("[test-model high]" in snapshot, snapshot)
                assertTrue("[ask user]" in snapshot, snapshot)
                assertTrue("[single agent]" in snapshot, snapshot)
                assertTrue("[cwd]" in snapshot || "[.]" in snapshot, snapshot)
                assertEquals(newSessionStatusBarRows(columns, settings), lines.size, snapshot)
            }
        }
    }

    @Test
    fun newSessionWorkingDirectoryButtonUsesTheDraft() = runTest {
        val columns = 80
        val workingDirectory = Path("workspace")
        var browseCount = 0

        runMosaicTest {
            val initial = setContentAndSnapshot {
                NewSessionStatusBar(
                    columns = columns,
                    settings = testSettings(workingDirectory),
                    dropdowns = RuntimeConfigurationDropdowns.remember(owner = Unit),
                    onBrowseWorkingDirectory = { browseCount += 1 },
                    onOpenSettings = {},
                )
            }
            val buttonStart = initial.indexOf("[workspace]")
            assertTrue(buttonStart >= 0, initial)

            click(buttonStart + 1)
            assertEquals(1, browseCount)
        }
    }
}

private fun testSettings(workingDirectory: Path): KodexAgentSettings = KodexAgentSettings(
    model = OpenAiModelId("test-model"),
    cwd = workingDirectory,
    reasoning = Reasoning(effort = ReasoningEffort.High),
)

private suspend fun TestMosaic<String>.click(column: Int) {
    sendMouseEvent(
        MouseEvent(column, 0, MouseEvent.Type.Press, MouseEvent.Button.Left),
    )
    awaitSnapshot()
    sendMouseEvent(MouseEvent(column, 0, MouseEvent.Type.Release))
    awaitSnapshot()
}

private suspend fun TestMosaic<String>.awaitSnapshotContaining(expected: String): String {
    var latest = ""
    repeat(5) {
        latest = try {
            awaitSnapshot()
        } catch (_: TimeoutCancellationException) {
            draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
        }
        if (expected in latest) return latest
    }
    assertTrue(expected in latest, latest)
    return latest
}
