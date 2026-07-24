package io.github.stream29.codex.lite.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ModeKind
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.tool.applypatch.ApplyPatchTools
import io.github.stream29.codex.lite.tool.currenttime.CurrentTimeTools
import io.github.stream29.codex.lite.tool.getcontextremaining.GetContextRemainingTools
import io.github.stream29.codex.lite.tool.multiagent.MultiAgentTools
import io.github.stream29.codex.lite.tool.plan.PlanTools
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputTools
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchTools
import io.github.stream29.codex.lite.tool.unifiedexec.UnifiedExecTools
import io.github.stream29.codex.lite.tool.webrun.WebRunTools
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
val codexRequestProjectionTest by testSuite {
    test("request tools combine fixed mode-dependent and dynamic specs") {
        val toolSearchSpec = ToolSearchTools.createToolSearchSpec()
        val fixedSpecs = listOf(
            ApplyPatchTools.spec,
            CurrentTimeTools.spec,
            GetContextRemainingTools.spec,
            UnifiedExecTools.execCommandSpec,
            UnifiedExecTools.writeStdinSpec,
            WebRunTools.spec,
        ) + MultiAgentTools.specs
        val defaultSpecs = codexRequestToolSpecs(
            settings = CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                collaborationMode = ModeKind.Default,
            ),
            toolSearchToolSpec = toolSearchSpec,
        )
        val planSpecs = codexRequestToolSpecs(
            settings = CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                collaborationMode = ModeKind.Plan,
            ),
            toolSearchToolSpec = toolSearchSpec,
        )

        assertEquals(
            fixedSpecs + PlanTools.spec + RequestUserInputTools.spec + toolSearchSpec,
            defaultSpecs,
        )
        assertEquals(
            fixedSpecs + RequestUserInputTools.spec + toolSearchSpec,
            planSpecs,
        )
    }

    test("storage identity projects to a stable provider thread id") {
        val first = "filesystem:/tmp/codex-lite/session-1".toCodexThreadId()

        assertEquals(first, "filesystem:/tmp/codex-lite/session-1".toCodexThreadId())
        assertNotEquals(first, "filesystem:/tmp/codex-lite/session-2".toCodexThreadId())
        assertEquals(first, Uuid.parse(first).toString())
    }
}
