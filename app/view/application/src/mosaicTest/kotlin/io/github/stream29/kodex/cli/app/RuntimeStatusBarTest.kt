package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.openai.ModeKind
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
