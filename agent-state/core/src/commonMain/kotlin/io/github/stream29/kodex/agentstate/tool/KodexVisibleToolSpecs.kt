package io.github.stream29.kodex.agentstate.tool

import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.tool.applypatch.ApplyPatchTools
import io.github.stream29.kodex.tool.awaitevent.AwaitEventTools
import io.github.stream29.kodex.tool.plan.PlanTools
import io.github.stream29.kodex.tool.readmedia.ReadMediaTools
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecTools

private val StaticToolSpecs: List<ToolSpec> = listOf(
    UnifiedExecTools.execCommandSpec,
    ApplyPatchTools.spec,
    ReadMediaTools.spec,
    PlanTools.spec,
    AwaitEventTools.spec,
)

/**
 * Returns the static 5 direct tool definitions visible in every Responses API request:
 * `exec_command`, `apply_patch`, `read_media`, `update_plan`, and `await_event`.
 */
public actual fun visibleToolSpecs(): List<ToolSpec> = StaticToolSpecs
