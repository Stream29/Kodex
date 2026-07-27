package io.github.stream29.codex.lite.openai.client

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputContentItem
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ImageDetail
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.ToolChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.schema.json.PropertyBuilder
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

private const val ImageOutputAcceptedMarker: String = "IMAGE_FUNCTION_OUTPUT_ACCEPTED"
private const val ImageInspectionCallId: String = "call_codex_lite_image_output_probe"

private val imageInspectionTool: ResponsesApiTool =
    ResponsesApiTool(
        name = "inspect_image",
        description = "Inspect the image returned by the host.",
        strict = true,
        parameters = PropertyBuilder().obj {
            additionalProperties = false
            property("path") {
                required = true
                string { description = "Path of the image to inspect." }
            }
        },
    )

val openAiFunctionCallImageOutputTest by testSuite {
    testFixture {
        OpenAiClient(
            authStore = codexAuthStore(),
            config = OpenAiClientConfig(clientVersion = testCodexClientVersion()),
        )
    } asParameterForEach {
        test(
            "responses accepts an image in function call output",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 180.seconds),
        ) { client ->
            val userMessage = ResponseItem.Message(
                role = MessageRole.User,
                content = listOf(
                    ContentItem.InputText(
                        "The inspect_image call and its result below have already completed. " +
                            "Reply with exactly $ImageOutputAcceptedMarker.",
                    ),
                ),
            )
            val request =
                ResponsesApiRequest(
                    model = testCodexModel(),
                    input = listOf(
                        userMessage,
                        ResponseItem.FunctionCall(
                            name = imageInspectionTool.name,
                            arguments = """{"path":"fixture.png"}""",
                            callId = ImageInspectionCallId,
                        ),
                        ResponseItem.FunctionCallOutput(
                            callId = ImageInspectionCallId,
                            output = FunctionCallOutputPayload(
                                body = FunctionCallOutputBody.ContentItems(
                                    listOf(
                                        FunctionCallOutputContentItem.InputImage(
                                            imageUrl = png64x32DataUrl,
                                            detail = ImageDetail.High,
                                        ),
                                    ),
                                ),
                                success = true,
                            ),
                        ),
                    ),
                    tools = listOf(imageInspectionTool),
                    toolChoice = ToolChoice.None,
                    store = false,
                )
            val events = withContext(Dispatchers.Default) {
                client.createResponse(request).toList()
            }
            events.requireCompleted("image function output")

            assertTrue(
                events.assistantText().contains(ImageOutputAcceptedMarker),
                "Expected the assistant to consume the image function output.",
            )
        }
    }
}

private fun List<ResponsesStreamEvent>.outputItems(): List<ResponseItem> =
    filterIsInstance<ResponsesStreamEvent.OutputItemDone>()
        .map(ResponsesStreamEvent.OutputItemDone::item)

private fun List<ResponsesStreamEvent>.assistantText(): String =
    outputItems()
        .filterIsInstance<ResponseItem.Message>()
        .filter { it.role == MessageRole.Assistant }
        .flatMap(ResponseItem.Message::content)
        .joinToString(separator = "") { item ->
            when (item) {
                is ContentItem.InputText -> item.text
                is ContentItem.OutputText -> item.text
                is ContentItem.InputImage -> ""
            }
        }

private fun List<ResponsesStreamEvent>.requireCompleted(probe: String) {
    filterIsInstance<ResponsesStreamEvent.Failed>().firstOrNull()?.let { event ->
        fail("$probe failed: ${event.response.error?.message ?: event.response}")
    }
    filterIsInstance<ResponsesStreamEvent.Incomplete>().firstOrNull()?.let { event ->
        fail("$probe was incomplete: ${event.response}")
    }
    filterIsInstance<ResponsesStreamEvent.Completed>().lastOrNull()
        ?: fail("$probe did not emit response.completed. Events: $this")
}
