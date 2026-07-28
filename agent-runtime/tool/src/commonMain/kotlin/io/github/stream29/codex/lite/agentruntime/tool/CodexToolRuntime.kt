package io.github.stream29.codex.lite.agentruntime.tool

import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstate.tool.toDeferredToolSearchDocuments
import io.github.stream29.codex.lite.agentstorage.contract.latestValue
import io.github.stream29.codex.lite.hook.contract.tool.PreToolUseResult
import io.github.stream29.codex.lite.hook.contract.tool.ToolHooks
import io.github.stream29.codex.lite.hook.toolutils.runPreToolUse
import io.github.stream29.codex.lite.hook.toolutils.runPostToolUse
import io.github.stream29.codex.lite.hook.toolutils.toHookBlockedOutput
import io.github.stream29.codex.lite.mcp.contract.McpService
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.FreeformTool
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiNamespace
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.codex.lite.tool.applypatch.ApplyPatchToolClient
import io.github.stream29.codex.lite.tool.applypatch.ApplyPatchTools
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.tool.contract.ToolName
import io.github.stream29.codex.lite.tool.currenttime.CurrentTimeTools
import io.github.stream29.codex.lite.tool.imagegeneration.ImageGenerationToolClient
import io.github.stream29.codex.lite.tool.imagegeneration.ImageGenerationTools
import io.github.stream29.codex.lite.tool.toolsearch.SearchToolCallParams
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchEngine
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchResult
import io.github.stream29.codex.lite.tool.unifiedexec.UnifiedExecToolClient
import io.github.stream29.codex.lite.tool.unifiedexec.UnifiedExecTools
import io.github.stream29.codex.lite.tool.viewimage.ViewImageToolClient
import io.github.stream29.codex.lite.tool.viewimage.ViewImageTools
import io.github.stream29.codex.lite.tool.webrun.WebRunToolClient
import io.github.stream29.codex.lite.tool.webrun.WebRunTools
import io.github.stream29.codex.lite.utils.codexlitehome.CodexLiteHome
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import io.github.stream29.codex.lite.utils.shellclient.ShellSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.io.files.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Executes Codex Lite's fixed local tools, current MCP tools, and client
 * tool-search calls.
 *
 * Fixed tools and the search catalog are owned by this runtime. [mcpService]
 * remains application-owned; this runtime only reads its current tool
 * snapshot and never closes it.
 */
public class CodexToolRuntime internal constructor(
    private val delegate: CodexAgentRuntime,
    private val resourceScope: CoroutineScope,
    client: OpenAiClient,
    modelCatalog: OpenAiModelCatalog,
    shellSettings: StateFlow<ShellSettings>,
    private val mcpService: McpService,
    private val toolHooks: ToolHooks,
) : CodexAgentRuntime by delegate, AutoCloseable {
    private val toolSearchEngine: StateFlow<ToolSearchEngine> = mcpService.tools
        .map { tools ->
            ToolSearchEngine(tools.toDeferredToolSearchDocuments())
        }
        .stateIn(
            scope = resourceScope,
            started = SharingStarted.Eagerly,
            initialValue = ToolSearchEngine(
                mcpService.tools.value.toDeferredToolSearchDocuments(),
            ),
        )
    private lateinit var initialSettings: CodexAgentSettings
    private val workingDirectory: MutableStateFlow<Path> by lazy {
        MutableStateFlow(initialSettings.cwd)
    }
    private val fixedToolsDelegate = lazy {
        val imageOutputDirectory = MutableStateFlow(
            ImageGenerationTools.outputDirectory(CodexLiteHome, storage.id),
        )
        val unifiedExecClient = resourceScope.UnifiedExecToolClient(
            settings = shellSettings,
            workingDirectory = workingDirectory,
        )
        buildList {
            add(ApplyPatchTools.createTool(ApplyPatchToolClient(root = workingDirectory)))
            add(CurrentTimeTools.createTool())
            add(getContextRemainingTool(modelCatalog))
            addAll(UnifiedExecTools.createTools(unifiedExecClient))
            add(
                WebRunTools.createTool(
                    WebRunToolClient(
                        client = client,
                        sessionId = storage.id,
                        model = initialSettings.model,
                    ),
                ),
            )
            add(ViewImageTools.createTool(ViewImageToolClient(root = workingDirectory)))
            add(
                ImageGenerationTools.createTool(
                    ImageGenerationToolClient(client = client, root = workingDirectory),
                    outputDirectory = imageOutputDirectory,
                ),
            )
        }
    }
    private val fixedTools: List<Tool> by fixedToolsDelegate

    init {
        resourceScope.coroutineContext.job.invokeOnCompletion {
            if (fixedToolsDelegate.isInitialized()) {
                fixedTools.asReversed().forEach { tool ->
                    runCatching { tool.close() }
                }
            }
        }
    }

    override fun resume(): Flow<ResponsesStreamEvent> = channelFlow {
        val mcpTools = mcpService.tools.value.toList()
        while (true) {
            var pending = state.value as? CodexAgentStateValue.ToolPending
            if (pending == null) {
                delegate.resume().collect { send(it) }
                pending = state.value as? CodexAgentStateValue.ToolPending
                    ?: return@channelFlow
            }
            val settings = storage.settings.latestValue()
            if (!::initialSettings.isInitialized) {
                initialSettings = settings
            }
            workingDirectory.value = settings.cwd
            val toolsByName = (fixedTools + mcpTools).toToolMap()
            var handledCall = false
            for (call in pending.calls) {
                val output = when (call) {
                    is ResponseItem.ClientToolSearchCall -> toolSearchEngine.value.handle(call)
                    is ResponseItem.FunctionCall,
                    is ResponseItem.CustomToolCall,
                    -> {
                        val tool = toolsByName[call.toolName]
                        if (tool == null && call.toolName.namespace?.startsWith("mcp__") == true) {
                            call.unavailable("The MCP tool is no longer available in the current catalog.")
                        } else if (tool == null) {
                            continue
                        } else {
                            handleToolCall(tool, call)
                        }
                    }
                }
                completeToolCall(output)
                handledCall = true
            }
            if (!handledCall) {
                return@channelFlow
            }
            if (state.value is CodexAgentStateValue.ToolPending) {
                return@channelFlow
            }
        }
    }.buffer(Channel.UNLIMITED)

    private suspend fun handleToolCall(
        tool: Tool,
        call: ResponseItem.ToolCall,
    ): ResponseItem.ToolCallOutput {
        when (val result = toolHooks.runPreToolUse(delegate.storage, call)) {
            is PreToolUseResult.Block -> {
                return call.toHookBlockedOutput(result.reason)
            }

            PreToolUseResult.Continue -> Unit
        }
        val output = tool.handle(call)
        toolHooks.runPostToolUse(
            storage = delegate.storage,
            call = call,
            output = output,
        )
        return output
    }

    /** Releases runtime-owned tool resources without closing [mcpService]. */
    override fun close() {
        resourceScope.cancel()
    }
}

/**
 * Adds the complete ordinary Codex tool layer.
 *
 * Fixed tools are constructed from this runtime's current Agent settings.
 * [shellSettings] remains live for shell selection. Generated images are
 * persisted below the process-wide Codex Lite home. [mcpService] supplies the
 * only externally owned tools.
 */
public fun CodexAgentRuntime.toolRuntime(
    client: OpenAiClient,
    modelCatalog: OpenAiModelCatalog,
    shellSettings: StateFlow<ShellSettings>,
    mcpService: McpService,
    toolHooks: ToolHooks,
): CodexToolRuntime {
    val resourceScope = supervisorChildScope()
    return try {
        CodexToolRuntime(
            delegate = this,
            resourceScope = resourceScope,
            client = client,
            modelCatalog = modelCatalog,
            shellSettings = shellSettings,
            mcpService = mcpService,
            toolHooks = toolHooks,
        )
    } catch (failure: Throwable) {
        resourceScope.cancel()
        throw failure
    }
}

private fun List<Tool>.toToolMap(): Map<ToolName, Tool> {
    val routes = flatMap { tool ->
        tool.routingNames().map { toolName -> toolName to tool }
    }
    val duplicateNames = routes
        .groupBy(keySelector = { (toolName) -> toolName })
        .filterValues { routesForName -> routesForName.size > 1 }
        .keys
    require(duplicateNames.isEmpty()) {
        "Multiple tools handle the same name: ${duplicateNames.joinToString()}"
    }
    return routes.toMap()
}

private fun Tool.routingNames(): List<ToolName> {
    val names = when (val spec = spec) {
        is ResponsesApiTool -> listOf(ToolName.plain(spec.name))
        is FreeformTool -> listOf(ToolName.plain(spec.name))
        is ResponsesApiNamespace -> spec.tools.map { namespaceTool ->
            when (namespaceTool) {
                is ResponsesApiTool -> ToolName.namespaced(spec.name, namespaceTool.name)
            }
        }

        is ToolSpec.ImageGeneration,
        is ToolSpec.ToolSearch,
        is ToolSpec.WebSearch -> error("CodexToolRuntime only accepts callable local tool specs.")
    }
    require(names.isNotEmpty()) { "A local Tool must expose at least one callable name." }
    return names
}

private val ResponseItem.ToolCall.toolName: ToolName
    get() = when (this) {
        is ResponseItem.FunctionCall -> ToolName(name = name, namespace = namespace)
        is ResponseItem.CustomToolCall -> ToolName(name = name, namespace = namespace)
        is ResponseItem.ClientToolSearchCall -> error("Client tool search calls have no tool name.")
    }

private fun ToolSearchEngine.handle(
    call: ResponseItem.ClientToolSearchCall,
): ResponseItem.ClientToolSearchOutput {
    val result = try {
        val arguments = OpenAiJsonCodec.decodeFromJsonElement<SearchToolCallParams>(call.arguments)
        search(arguments)
    } catch (_: SerializationException) {
        null
    }
    return ResponseItem.ClientToolSearchOutput(
        callId = call.callId,
        status = "completed",
        tools = (result as? ToolSearchResult.Success)?.tools.orEmpty(),
    )
}

private fun ResponseItem.ToolCall.unavailable(message: String): ResponseItem.ToolCallOutput =
    when (this) {
        is ResponseItem.FunctionCall -> ResponseItem.FunctionCallOutput(
            callId = callId,
            output = FunctionCallOutputPayload.fromText(message).copy(success = false),
        )

        is ResponseItem.CustomToolCall -> ResponseItem.CustomToolCallOutput(
            callId = callId,
            output = FunctionCallOutputPayload.fromText(message).copy(success = false),
        )

        is ResponseItem.ClientToolSearchCall -> error("Client tool-search calls have a dedicated output type.")
    }
