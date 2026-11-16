package io.github.stream29.kodex.agentruntime.impl

import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.tool.applypatch.ApplyPatchTool
import io.github.stream29.kodex.tool.applypatch.ApplyPatchToolClient
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.plan.updatePlanTool
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolClient
import io.github.stream29.kodex.tool.readmedia.ReadMediaTools
import io.github.stream29.kodex.tool.unifiedexec.KodexAgentIdEnvironmentVariable
import io.github.stream29.kodex.tool.unifiedexec.KodexHomeEnvironmentVariable
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecToolClient
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecTools
import io.github.stream29.kodex.utils.coroutines.closeOnFailure
import kotlinx.io.files.Path

internal data class KodexAgentFixedTools(
    val tools: List<Tool>,
    val unifiedExecToolClient: UnifiedExecToolClient,
)

internal fun KodexAgentState.fixedTools(
    dependencies: KodexAgentDependencies,
): KodexAgentFixedTools {
    val agentSettingsProvider: suspend () -> KodexAgentSettings = {
        storage.getSettingsAt(storage.latestIndex())
    }
    val workingDirectoryProvider: suspend () -> Path = {
        agentSettingsProvider().cwd
    }
    val environmentProvider: suspend () -> Map<String, String> = {
        buildMap {
            put(KodexHomeEnvironmentVariable, dependencies.contextSettings.value.kodexHome.toString())
            agentId?.let { id -> put(KodexAgentIdEnvironmentVariable, id.toString()) }
        }
    }
    val unifiedExecClient = UnifiedExecToolClient(
        settingsProvider = { dependencies.shellSettings.value },
        workingDirectoryProvider = workingDirectoryProvider,
        environmentProvider = environmentProvider,
    )
    return unifiedExecClient.closeOnFailure {
        KodexAgentFixedTools(
            tools = listOf(
                ApplyPatchTool(
                    ApplyPatchToolClient(
                        workingDirectoryProvider = workingDirectoryProvider,
                    ),
                ),
                ReadMediaTools.createTool(
                    ReadMediaToolClient(
                        workingDirectoryProvider = workingDirectoryProvider,
                    ),
                ),
                updatePlanTool(),
                UnifiedExecTools.createTool(unifiedExecClient),
            ),
            unifiedExecToolClient = unifiedExecClient,
        )
    }
}
