package io.github.stream29.kodex.agentruntime.impl

import io.github.stream29.kodex.agentsession.contract.AgentPathResolver
import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.tool.toDeferredToolSearchDocuments
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.latestValue
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.tool.applypatch.ApplyPatchToolClient
import io.github.stream29.kodex.tool.applypatch.ApplyPatchTools
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.currenttime.CurrentTimeTools
import io.github.stream29.kodex.tool.getcontextremaining.getContextRemainingTool
import io.github.stream29.kodex.tool.imagegeneration.ImageGenerationToolClient
import io.github.stream29.kodex.tool.imagegeneration.ImageGenerationTools
import io.github.stream29.kodex.tool.multiagent.followupTaskTool
import io.github.stream29.kodex.tool.multiagent.interruptAgentTool
import io.github.stream29.kodex.tool.multiagent.listAgentsTool
import io.github.stream29.kodex.tool.multiagent.sendMessageTool
import io.github.stream29.kodex.tool.multiagent.spawnAgentTool
import io.github.stream29.kodex.tool.multiagent.waitAgentTool
import io.github.stream29.kodex.tool.plan.updatePlanTool
import io.github.stream29.kodex.tool.toolsearch.ToolSearchEngine
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecToolClient
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecTools
import io.github.stream29.kodex.tool.viewimage.ViewImageToolClient
import io.github.stream29.kodex.tool.viewimage.ViewImageTools
import io.github.stream29.kodex.tool.webrun.WebRunToolClient
import io.github.stream29.kodex.tool.webrun.WebRunTools
import io.github.stream29.kodex.utils.kodexhome.KodexHome
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.io.files.Path

internal data class KodexAgentFixedTools(
    val tools: List<Tool>,
    val unifiedExecToolClient: UnifiedExecToolClient,
)

internal fun KodexAgentState.fixedTools(
    dependencies: KodexAgentDependencies,
    agentPathResolver: AgentPathResolver,
    pendingSteer: StateFlow<List<StableCleanEvent.Steerable>>,
): KodexAgentFixedTools {
    val agentSettingsProvider: suspend () -> KodexAgentSettings = {
        storage.settings.latestValue()
    }
    val workingDirectoryProvider: suspend () -> Path = {
        agentSettingsProvider().cwd
    }
    val modelProvider: suspend () -> OpenAiModelId = {
        agentSettingsProvider().model
    }
    val unifiedExecClient = UnifiedExecToolClient(
        settingsProvider = { dependencies.shellSettings.value },
        workingDirectoryProvider = workingDirectoryProvider,
    )
    return unifiedExecClient.closeOnFailure {
        KodexAgentFixedTools(
            tools = buildList {
                add(
                    ApplyPatchTools.createTool(
                        ApplyPatchToolClient(
                            workingDirectoryProvider = workingDirectoryProvider,
                        ),
                    ),
                )
                add(CurrentTimeTools.createTool())
                add(getContextRemainingTool(dependencies.modelCatalog))
                add(updatePlanTool())
                add(spawnAgentTool(agentPathResolver))
                add(sendMessageTool(agentPathResolver))
                add(followupTaskTool(agentPathResolver))
                add(waitAgentTool(pendingSteer))
                add(interruptAgentTool(agentPathResolver))
                add(listAgentsTool(agentPathResolver))
                addAll(UnifiedExecTools.createTools(unifiedExecClient))
                add(
                    WebRunTools.createTool(
                        WebRunToolClient(
                            client = dependencies.client,
                            sessionId = storage.id,
                            modelProvider = modelProvider,
                        ),
                    ),
                )
                add(
                    ViewImageTools.createTool(
                        ViewImageToolClient(
                            workingDirectoryProvider = workingDirectoryProvider,
                        ),
                    ),
                )
                add(
                    ImageGenerationTools.createTool(
                        client = ImageGenerationToolClient(
                            client = dependencies.client,
                            workingDirectoryProvider = workingDirectoryProvider,
                        ),
                        outputDirectory = ImageGenerationTools.outputDirectory(KodexHome, storage.id),
                    ),
                )
            },
            unifiedExecToolClient = unifiedExecClient,
        )
    }
}

internal fun KodexAgentState.toolSearchState(
    mcpService: McpService,
): StateFlow<ToolSearchEngine> =
    mcpService.tools
        .map { tools ->
            ToolSearchEngine(tools.toDeferredToolSearchDocuments())
        }
        .stateIn(
            scope = this,
            started = SharingStarted.Eagerly,
            initialValue = ToolSearchEngine(
                mcpService.tools.value.toDeferredToolSearchDocuments(),
            ),
        )

private inline fun <Resource : AutoCloseable, Result> Resource.closeOnFailure(
    block: () -> Result,
): Result =
    try {
        block()
    } catch (failure: Throwable) {
        try {
            close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
