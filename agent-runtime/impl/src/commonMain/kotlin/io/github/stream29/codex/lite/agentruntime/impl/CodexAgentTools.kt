package io.github.stream29.codex.lite.agentruntime.impl

import io.github.stream29.codex.lite.agentsession.contract.AgentPathResolver
import io.github.stream29.codex.lite.agentsession.contract.CodexAgentDependencies
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.agentstate.tool.toDeferredToolSearchDocuments
import io.github.stream29.codex.lite.agentstorage.contract.latestValue
import io.github.stream29.codex.lite.mcp.contract.McpService
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.tool.applypatch.ApplyPatchToolClient
import io.github.stream29.codex.lite.tool.applypatch.ApplyPatchTools
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.tool.currenttime.CurrentTimeTools
import io.github.stream29.codex.lite.tool.getcontextremaining.getContextRemainingTool
import io.github.stream29.codex.lite.tool.imagegeneration.ImageGenerationToolClient
import io.github.stream29.codex.lite.tool.imagegeneration.ImageGenerationTools
import io.github.stream29.codex.lite.tool.multiagent.multiAgentTools
import io.github.stream29.codex.lite.tool.plan.updatePlanTool
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchEngine
import io.github.stream29.codex.lite.tool.unifiedexec.UnifiedExecToolClient
import io.github.stream29.codex.lite.tool.unifiedexec.UnifiedExecTools
import io.github.stream29.codex.lite.tool.viewimage.ViewImageToolClient
import io.github.stream29.codex.lite.tool.viewimage.ViewImageTools
import io.github.stream29.codex.lite.tool.webrun.WebRunToolClient
import io.github.stream29.codex.lite.tool.webrun.WebRunTools
import io.github.stream29.codex.lite.utils.codexlitehome.CodexLiteHome
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.io.files.Path

internal fun CodexAgentState.fixedTools(
    dependencies: CodexAgentDependencies,
    agentPathResolver: AgentPathResolver,
): List<Tool> {
    val agentSettingsProvider: suspend () -> CodexAgentSettings = {
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
        buildList {
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
            addAll(multiAgentTools(agentPathResolver))
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
                    outputDirectory = ImageGenerationTools.outputDirectory(CodexLiteHome, storage.id),
                ),
            )
        }
    }
}

internal fun CodexAgentState.toolSearchState(
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
