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
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.newsession.NewSessionViewState
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.openai.ModeKind
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ModelServiceTier
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ReasoningEffortPreset
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
    fun modesUseConciseBuildAndPlanLabels() {
        assertEquals("build", ModeKind.Default.displayName())
        assertEquals("plan", ModeKind.Plan.displayName())
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
        assertTrue(regular.startsWith("cwd: …"), regular)
        assertTrue(regular.endsWith("workspace"), regular)
        assertTrue(regular.removePrefix("cwd: ").terminalCellWidth() <= 16, regular)

        val wide = workingDirectoryStatusLabel(workingDirectory, columns = 120)
        assertTrue(wide.startsWith("cwd: …"), wide)
        assertTrue(wide.endsWith("workspace"), wide)
        assertTrue(wide.removePrefix("cwd: ").terminalCellWidth() <= 28, wide)
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
                mode = ModeKind.Default,
            ),
        )
        lateinit var dropdowns: RuntimeConfigurationDropdowns

        runMosaicTest {
            val initial = setContentAndSnapshot {
                dropdowns = RuntimeConfigurationDropdowns.remember(owner = Unit)
                TuiPopupHost(modifier = Modifier.width(60).height(12)) {
                    Column {
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
                        onModeSelected = { mode ->
                            configuration = configuration.copy(mode = mode)
                        },
                    )
                }
            }
            assertTrue("[gpt-5.6-sol max]" in initial, initial)
            assertTrue("[build]" in initial, initial)
            assertFalse("build mode" in initial, initial)
            assertFalse("default" in initial, initial)

            dropdowns.model.expand()
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
    fun newSessionSettingsButtonIsSeparatedAtRightEdge() = runTest {
        val columns = 80

        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                NewSessionStatusBar(
                    columns = columns,
                    state = NewSessionViewState(
                        settings = KodexNewSessionSettings(),
                        workingDirectory = Path("."),
                        newLineKey = NewLineKey.ShiftEnter,
                        codexHome = Path("codex-home"),
                    ),
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
    fun newSessionWorkingDirectoryButtonUsesTheDraftAndDisablesWhileCreating() = runTest {
        val columns = 80
        val workingDirectory = Path("workspace")
        var creating by mutableStateOf(false)
        val selectedDirectories = mutableListOf<Path>()

        runMosaicTest {
            val initial = setContentAndSnapshot {
                NewSessionStatusBar(
                    columns = columns,
                    state = NewSessionViewState(
                        settings = KodexNewSessionSettings(),
                        workingDirectory = workingDirectory,
                        newLineKey = NewLineKey.ShiftEnter,
                        codexHome = Path("codex-home"),
                        creating = creating,
                    ),
                    dropdowns = RuntimeConfigurationDropdowns.remember(owner = Unit),
                    onBrowseWorkingDirectory = { directory -> selectedDirectories += directory },
                    onOpenSettings = {},
                )
            }
            val buttonStart = initial.indexOf("[cwd: workspace]")
            assertTrue(buttonStart >= 0, initial)

            sendMouseEvent(
                MouseEvent(buttonStart + 1, 0, MouseEvent.Type.Press, MouseEvent.Button.Left),
            )
            awaitSnapshot()
            sendMouseEvent(MouseEvent(buttonStart + 1, 0, MouseEvent.Type.Release))
            awaitSnapshot()
            assertEquals(listOf(workingDirectory), selectedDirectories)

            creating = true
            awaitSnapshot()
            sendMouseEvent(
                MouseEvent(buttonStart + 1, 0, MouseEvent.Type.Press, MouseEvent.Button.Left),
            )
            sendMouseEvent(MouseEvent(buttonStart + 1, 0, MouseEvent.Type.Release))
            assertEquals(listOf(workingDirectory), selectedDirectories)
        }
    }
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
