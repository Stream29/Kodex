package io.github.stream29.kodex.cli.sessiontitle

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

val openAiSessionTitleGeneratorTest by testSuite {
    test("projects the isolated title request and decodes structured output") {
        lateinit var captured: ResponsesApiRequest
        val client = mockOpenAiClient {
            createResponse { request ->
                captured = request
                flowOf(
                    ResponsesStreamEvent.OutputTextDelta(
                        itemId = "message_1",
                        outputIndex = 0,
                        contentIndex = 0,
                        delta = "{\"title\":\"Add automatic session titles\"}",
                    ),
                    ResponsesStreamEvent.Completed(Response(id = "response_1")),
                )
            }
        }

        val result = OpenAiSessionTitleGenerator(client).generateTitle(
            userText = "Please add automatic session titles.",
            model = OpenAiModelId("title-model"),
            reasoningEffort = ReasoningEffort.High,
        )

        assertEquals(
            SessionTitleGenerationResult.Generated("Add automatic session titles"),
            result,
        )
        assertEquals(OpenAiModelId("title-model"), captured.model)
        assertEquals(false, captured.store)
        assertTrue(captured.tools.isEmpty())
        assertEquals(false, captured.parallelToolCalls)
        assertEquals(ReasoningEffort.High, captured.reasoning.effort)
        val input = assertIs<ResponseItem.Message>(captured.input.single())
        assertEquals(MessageRole.User, input.role)
        assertEquals(
            "User prompt:\nPlease add automatic session titles.\n",
            assertIs<ContentItem.InputText>(input.content.single()).text,
        )
        val schema = captured.text.format?.schema ?: error("Missing title schema")
        assertEquals("object", schema.getValue("type").jsonPrimitive.content)
        assertEquals(false, schema.getValue("additionalProperties").jsonPrimitive.content.toBoolean())
        assertEquals("session_title", captured.text.format?.name)
        assertTrue(captured.instructions.contains("Write the title in the user's locale."))
    }

    test("limits request input by Unicode scalar count") {
        lateinit var captured: ResponsesApiRequest
        val client = mockOpenAiClient {
            createResponse { request ->
                captured = request
                flowOf(
                    ResponsesStreamEvent.Completed(
                        Response(id = "response_1", outputText = "A sufficiently descriptive title"),
                    ),
                )
            }
        }
        val input = "😀".repeat(SessionTitleInputLimit + 1)

        OpenAiSessionTitleGenerator(client).generateTitle(
            input,
            OpenAiModelId("title-model"),
            ReasoningEffort.Low,
        )

        val prompt = assertIs<ContentItem.InputText>(
            assertIs<ResponseItem.Message>(captured.input.single()).content.single(),
        ).text
        assertEquals("User prompt:\n" + "😀".repeat(SessionTitleInputLimit) + "\n", prompt)
    }

    test("accepts completed assistant output when no text deltas arrive") {
        val client = mockOpenAiClient {
            createResponse {
                flowOf(
                    ResponsesStreamEvent.OutputItemDone(
                        outputIndex = 0,
                        item = ResponseItem.Message(
                            role = MessageRole.Assistant,
                            content = listOf(ContentItem.OutputText("Title: Locate foo_bar creation?")),
                        ),
                    ),
                    ResponsesStreamEvent.Completed(Response(id = "response_1")),
                )
            }
        }

        assertEquals(
            SessionTitleGenerationResult.Generated("Locate foo_bar creation"),
            OpenAiSessionTitleGenerator(client).generateTitle(
                "Where is foo_bar?",
                OpenAiModelId("title-model"),
                ReasoningEffort.Low,
            ),
        )
    }

    test("rejects a stream without a completed response") {
        val client = mockOpenAiClient {
            createResponse {
                flowOf(
                    ResponsesStreamEvent.OutputTextDelta(
                        itemId = "message_1",
                        outputIndex = 0,
                        contentIndex = 0,
                        delta = "A sufficiently descriptive title",
                    ),
                )
            }
        }

        assertIs<SessionTitleGenerationResult.Rejected>(
            OpenAiSessionTitleGenerator(client).generateTitle(
                "Prompt",
                OpenAiModelId("title-model"),
                ReasoningEffort.Low,
            ),
        )
    }

    test("normalizes title prefix punctuation and length") {
        assertEquals("Investigate flaky test", sanitizeGeneratedSessionTitle("  \"Investigate flaky test.\"  "))
        assertEquals("Locate foo_bar creation", sanitizeGeneratedSessionTitle("TITLE  Locate foo_bar creation?"))
        assertEquals(null, sanitizeGeneratedSessionTitle("Fix bug"))
        assertEquals(null, sanitizeGeneratedSessionTitle("Fix TypeScript\nschema"))
        assertEquals(
            "Refactor authentication middleware…",
            sanitizeGeneratedSessionTitle("Refactor authentication middleware configuration safely"),
        )
    }
}
