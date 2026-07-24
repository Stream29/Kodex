package io.github.stream29.codex.lite.agentstate.impl

import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ModeKind
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.tool.applypatch.ApplyPatchTools
import io.github.stream29.codex.lite.tool.currenttime.CurrentTimeTools
import io.github.stream29.codex.lite.tool.getcontextremaining.GetContextRemainingTools
import io.github.stream29.codex.lite.tool.multiagent.MultiAgentTools
import io.github.stream29.codex.lite.tool.plan.PlanTools
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputTools
import io.github.stream29.codex.lite.tool.unifiedexec.UnifiedExecTools
import io.github.stream29.codex.lite.tool.webrun.WebRunTools

private val DirectToolSpecs: List<ToolSpec> = buildList {
    add(ApplyPatchTools.spec)
    add(CurrentTimeTools.spec)
    add(GetContextRemainingTools.spec)
    add(UnifiedExecTools.execCommandSpec)
    add(UnifiedExecTools.writeStdinSpec)
    add(WebRunTools.spec)
    addAll(MultiAgentTools.specs)
}

internal fun codexRequestToolSpecs(
    settings: CodexAgentSettings,
    toolSearchToolSpec: ToolSpec.ToolSearch,
): List<ToolSpec> =
    buildList {
        addAll(DirectToolSpecs)
        if (settings.collaborationMode == ModeKind.Default) {
            add(PlanTools.spec)
        }
        add(RequestUserInputTools.spec)
        add(toolSearchToolSpec)
    }
