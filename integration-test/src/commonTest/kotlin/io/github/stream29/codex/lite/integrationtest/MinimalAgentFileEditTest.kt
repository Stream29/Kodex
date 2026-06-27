package io.github.stream29.codex.lite.integrationtest

import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.FreeformTool
import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesApiNamespace
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.tool.applypatch.ApplyPatchToolClient
import io.github.stream29.codex.lite.tool.applypatch.ApplyPatchTools
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.tool.contract.ToolCallPayload
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class MinimalAgentFileEditTest {
    @Test
    fun agentCompletesFileEditTaskWithApplyPatch() = runTest {
        withTempDir { root ->
            val target = Path(root, "notes.txt")
            SystemCoroutineFileSystem.writeString(
                target,
                """
                title
                before
                done
                """.trimIndent() + "\n",
            )

            val tool = ApplyPatchTools.createTool(ApplyPatchToolClient(root = root))
            val requests = mutableListOf<ResponseItemsRequestSnapshot>()
            val client = scriptedFileEditClient(requests)

            val result = try {
                MinimalAgent(client, listOf(tool)).run(
                    initialInput = listOf(
                        ResponseItem.Message(
                            role = MessageRole.User,
                            content = listOf(ContentItem.InputText("Change `before` to `after` in notes.txt.")),
                        ),
                    ),
                )
            } finally {
                tool.close()
                client.close()
            }

            assertEquals(
                """
                title
                after
                done
                """.trimIndent() + "\n",
                SystemCoroutineFileSystem.readString(target),
            )
            assertEquals("Edited notes.txt.", result.finalAssistantMessage)
            assertEquals(2, result.samplingRequests)
            assertEquals(listOf(ApplyPatchTools.spec), requests.first().tools)
            assertTrue(requests.last().input.any { it is ResponseItem.CustomToolCallOutput })
        }
    }

    private fun scriptedFileEditClient(
        requests: MutableList<ResponseItemsRequestSnapshot>,
    ): OpenAiClient =
        mockOpenAiClient {
            createResponse { request ->
                requests += ResponseItemsRequestSnapshot(
                    input = request.input,
                    tools = request.tools,
                )

                when (requests.size) {
                    1 -> flowOf(
                        ResponsesStreamEvent.OutputItemDone(
                            outputIndex = 0,
                            item = ResponseItem.CustomToolCall(
                                callId = "call_apply_patch_1",
                                name = ApplyPatchTools.Name,
                                input = """
                                *** Begin Patch
                                *** Update File: notes.txt
                                @@
                                 title
                                -before
                                +after
                                 done
                                *** End Patch
                                """.trimIndent(),
                            ),
                        ),
                        ResponsesStreamEvent.Completed(
                            response = Response(
                                id = "response_1",
                                endTurn = false,
                            ),
                        ),
                    )

                    2 -> {
                        val output = requests.last().input
                            .filterIsInstance<ResponseItem.CustomToolCallOutput>()
                            .single()
                        assertEquals("call_apply_patch_1", output.callId)
                        assertEquals(true, output.output.success)

                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = ResponseItem.Message(
                                    role = MessageRole.Assistant,
                                    content = listOf(ContentItem.OutputText("Edited notes.txt.")),
                                ),
                            ),
                            ResponsesStreamEvent.Completed(
                                response = Response(
                                    id = "response_2",
                                    endTurn = true,
                                ),
                            ),
                        )
                    }

                    else -> fail("Unexpected extra sampling request.")
                }
            }
        }

    private suspend fun withTempDir(block: suspend (Path) -> Unit) {
        val root = Path(SystemTemporaryDirectory, "codex-lite-integration-agent-${Random.nextLong()}")
        SystemCoroutineFileSystem.createDirectories(root)
        try {
            block(root)
        } finally {
            deleteRecursively(SystemCoroutineFileSystem, root)
        }
    }

    private suspend fun deleteRecursively(fileSystem: CoroutineFileSystem, path: Path) {
        val metadata = fileSystem.metadataOrNull(path) ?: return
        if (metadata.isDirectory) {
            fileSystem.list(path).forEach { child ->
                deleteRecursively(fileSystem, child)
            }
        }
        fileSystem.delete(path, mustExist = false)
    }
}

private data class ResponseItemsRequestSnapshot(
    val input: List<ResponseItem>,
    val tools: List<ToolSpec>,
)

private data class MinimalAgentResult(
    val finalAssistantMessage: String?,
    val samplingRequests: Int,
)

private class MinimalAgent(
    private val client: OpenAiClient,
    private val tools: List<Tool>,
    private val model: OpenAiModelId = OpenAiModelId("test-model"),
    private val maxSamplingRequests: Int = 8,
) {
    private val toolsByRoute = tools
        .flatMap { tool -> tool.routes().map { route -> route to tool } }
        .toMap()

    suspend fun run(initialInput: List<ResponseItem>): MinimalAgentResult {
        val history = initialInput.toMutableList()
        var samplingRequests = 0
        var finalAssistantMessage: String? = null

        while (true) {
            samplingRequests += 1
            if (samplingRequests > maxSamplingRequests) {
                fail("Agent exceeded $maxSamplingRequests sampling requests.")
            }

            var needsFollowUp = false
            client.createResponse(
                ResponsesApiRequest(
                    model = model,
                    input = history.toList(),
                    tools = tools.map(Tool::spec),
                ),
            ).collect { event ->
                when (event) {
                    is ResponsesStreamEvent.OutputItemDone -> {
                        history += event.item
                        when (val item = event.item) {
                            is ResponseItem.FunctionCall -> {
                                history += runFunctionTool(item)
                                needsFollowUp = true
                            }

                            is ResponseItem.CustomToolCall -> {
                                history += runCustomTool(item)
                                needsFollowUp = true
                            }

                            is ResponseItem.Message -> {
                                if (item.role == MessageRole.Assistant) {
                                    finalAssistantMessage = item.text()
                                }
                            }

                            else -> Unit
                        }
                    }

                    is ResponsesStreamEvent.Completed -> {
                        if (event.response.endTurn == false) {
                            needsFollowUp = true
                        }
                    }

                    is ResponsesStreamEvent.Failed -> {
                        fail("Response failed: ${event.response.error?.message ?: "unknown error"}")
                    }

                    is ResponsesStreamEvent.Incomplete -> {
                        fail("Response incomplete: ${event.response.incompleteDetails?.reason ?: "unknown reason"}")
                    }

                    else -> Unit
                }
            }

            if (!needsFollowUp) {
                return MinimalAgentResult(
                    finalAssistantMessage = finalAssistantMessage,
                    samplingRequests = samplingRequests,
                )
            }
        }
    }

    private suspend fun runFunctionTool(item: ResponseItem.FunctionCall): ResponseItem.FunctionCallOutput =
        ResponseItem.FunctionCallOutput(
            callId = item.callId,
            output = handleTool(
                route = ToolRoute(item.namespace, item.name),
                payload = ToolCallPayload.FunctionCall(item),
            ),
        )

    private suspend fun runCustomTool(item: ResponseItem.CustomToolCall): ResponseItem.CustomToolCallOutput =
        ResponseItem.CustomToolCallOutput(
            callId = item.callId,
            name = item.name,
            output = handleTool(
                route = ToolRoute(namespace = null, name = item.name),
                payload = ToolCallPayload.CustomToolCall(item),
            ),
        )

    private suspend fun handleTool(
        route: ToolRoute,
        payload: ToolCallPayload,
    ): FunctionCallOutputPayload {
        val tool = toolsByRoute[route] ?: return FunctionCallOutputPayload(
            body = FunctionCallOutputBody.Text("Unknown tool: ${route.displayName}"),
            success = false,
        )
        return tool.handle(payload)
    }
}

private data class ToolRoute(
    val namespace: String?,
    val name: String,
) {
    val displayName: String = namespace?.let { "$it.$name" } ?: name
}

private fun Tool.routes(): List<ToolRoute> =
    when (val spec = spec) {
        is ResponsesApiTool -> listOf(ToolRoute(namespace = null, name = spec.name))
        is FreeformTool -> listOf(ToolRoute(namespace = null, name = spec.name))
        is ResponsesApiNamespace -> spec.tools.map { tool ->
            val apiTool = assertIs<ResponsesApiTool>(tool)
            ToolRoute(namespace = spec.name, name = apiTool.name)
        }

        else -> emptyList()
    }

private fun ResponseItem.Message.text(): String =
    content.joinToString(separator = "") { item ->
        when (item) {
            is ContentItem.InputText -> item.text
            is ContentItem.OutputText -> item.text
            is ContentItem.InputImage -> ""
        }
    }
