package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.runtimeconfiguration.*
import io.github.stream29.kodex.cli.runtimeconfiguration.*

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
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ModelServiceTier
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ReasoningEffortPreset
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.availableServiceTiers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

private fun configurationState(
    configuration: RuntimeConfiguration,
    models: List<ModelInfo> = emptyList(),
): RuntimeConfigurationState = RuntimeConfigurationState(
    configuration,
    (models.map { it.slug } + configuration.model).distinct().map { model ->
        val info = models.firstOrNull { it.slug == model }
        RuntimeConfigurationModelOption(
            model,
            info?.supportedReasoningLevels?.map { it.effort }.orEmpty().ifEmpty { listOf(configuration.reasoning) },
            info?.availableServiceTiers().orEmpty().ifEmpty { listOf(ServiceTier.Default) },
        )
    },
)

private fun configurationViewModel(settings: KodexAgentSettings): RuntimeConfigurationViewModel =
    object : RuntimeConfigurationViewModel {
        override val state = MutableStateFlow(configurationState(RuntimeConfiguration(
            settings.model, settings.reasoning.effort, settings.serviceTier, settings.requestUserInputMode,
        )))
        override suspend fun updateModelConfiguration(model: OpenAiModelId, effort: ReasoningEffort, tier: ServiceTier) = Unit
        override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode) = Unit
        override fun close() = Unit
    }

val newSessionStatusBarTest by testSuite {
    test("requestUserInputModesUseExplicitLabels") {
        assertEquals("ask user", runtimeRequestUserInputModeLabel(RequestUserInputMode.AskUser))
        assertEquals("no question", runtimeRequestUserInputModeLabel(RequestUserInputMode.NoQuestion))
    }

    test("combinedConfigurationLabelOmitsOnlyTheDefaultTier") {
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

    test("modelMenuSelectsModelReasoningAndTierAcrossThreeLevels") {
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
                requestUserInputMode = RequestUserInputMode.AskUser,
            ),
        )
        lateinit var dropdowns: RuntimeConfigurationDropdowns

        runMosaicTest {
            val initial = setContentAndSnapshot {
                dropdowns = RuntimeConfigurationDropdowns.remember(owner = Unit)
                TuiPopupHost(modifier = Modifier.width(60).height(12)) {
                    Row {
                        RuntimeConfigurationTriggers(configurationState(configuration, listOf(modelInfo)), dropdowns)
                    }
                    RuntimeConfigurationMenus(
                        state = configurationState(configuration, listOf(modelInfo)),
                        dropdowns = dropdowns,
                        onConfigurationSelected = { selectedModel, effort, tier ->
                            configuration = configuration.copy(
                                model = selectedModel,
                                reasoning = effort,
                                tier = tier,
                            )
                        },
                        onRequestUserInputModeSelected = { mode ->
                            configuration = configuration.copy(requestUserInputMode = mode)
                        },
                    )
                }
            }
            assertTrue("[gpt-5.6-sol max]" in initial, initial)

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

    test("questionModeTriggerOpensAndSelectsNoQuestion") {
        val model = OpenAiModelId("test-model")
        var configuration by mutableStateOf(
            RuntimeConfiguration(
                model = model,
                reasoning = ReasoningEffort.High,
                tier = ServiceTier.Default,
                requestUserInputMode = RequestUserInputMode.AskUser,
            ),
        )

        runMosaicTest {
            val initial = setContentAndSnapshot {
                val dropdowns = RuntimeConfigurationDropdowns.remember(owner = Unit)
                TuiPopupHost(modifier = Modifier.width(60).height(8)) {
                    Row {
                        RuntimeConfigurationTriggers(configurationState(configuration), dropdowns)
                    }
                    RuntimeConfigurationMenus(
                        state = configurationState(configuration),
                        dropdowns = dropdowns,
                        onConfigurationSelected = { selectedModel, effort, tier ->
                            configuration = configuration.copy(
                                model = selectedModel,
                                reasoning = effort,
                                tier = tier,
                            )
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

    test("newSessionSettingsButtonIsSeparatedAtRightEdge") {
        val columns = 80

        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                NewSessionStatusBar(
                    columns = columns,
                    settings = testSettings(Path(".")),
                    runtimeConfiguration = configurationViewModel(testSettings(Path("."))),
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

    test("newSessionControlsStayCompleteAcrossSupportedWidths") {
        listOf(40, 60, 80, 120).forEach { columns ->
            runMosaicTest {
                val settings = testSettings(Path("."))
                val snapshot = setContentAndSnapshot {
                    NewSessionStatusBar(
                        columns = columns,
                        settings = settings,
                        runtimeConfiguration = configurationViewModel(settings),
                        dropdowns = RuntimeConfigurationDropdowns.remember(owner = columns),
                        onBrowseWorkingDirectory = {},
                        onOpenSettings = {},
                    )
                }
                val lines = snapshot.lines()

                assertTrue(lines.first().endsWith("[Settings]"), snapshot)
                assertTrue("[test-model high]" in snapshot, snapshot)
                assertTrue("[ask user]" in snapshot, snapshot)
                assertTrue("[cwd]" in snapshot || "[.]" in snapshot, snapshot)
                assertEquals(newSessionStatusBarRows(columns, settings), lines.size, snapshot)
            }
        }
    }

    test("newSessionWorkingDirectoryButtonUsesTheDraft") {
        val columns = 80
        val workingDirectory = Path("workspace")
        var browseCount = 0

        runMosaicTest {
            val initial = setContentAndSnapshot {
                NewSessionStatusBar(
                    columns = columns,
                    settings = testSettings(workingDirectory),
                    runtimeConfiguration = configurationViewModel(testSettings(workingDirectory)),
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

    test("draft statusbar drawing and button capture are clipped on tiny surfaces") {
        listOf(1, 8, 16, 32).forEach { columns ->
            var settingsClicks = 0
            runMosaicTest {
                val settings = testSettings(Path("."))
                val snapshot = setContentAndSnapshot {
                    NewSessionStatusBar(
                        columns, settings, configurationViewModel(settings),
                        RuntimeConfigurationDropdowns.remember(columns),
                        {}, { settingsClicks++ },
                    )
                }
                assertEquals(newSessionStatusBarRows(columns, settings), snapshot.lines().size)
                assertTrue(snapshot.lines().all { it.length <= (columns - 1).coerceAtLeast(1) }, snapshot)
                // A natural-width Settings control must not capture outside its clipped parent.
                sendMouseEvent(MouseEvent(columns + 2, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
                sendMouseEvent(MouseEvent(columns + 2, 0, MouseEvent.Type.Release))
                // Drain actual input through Mosaic's frame clock. A correctly ignored click
                // need not invalidate the drawing, so absence of a frame is not a failure.
                try { awaitSnapshot(100.milliseconds) } catch (_: TimeoutCancellationException) { }
                assertEquals(0, settingsClicks)
                assertFalse(snapshot.lines().any { it.length > columns }, snapshot)
            }
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
