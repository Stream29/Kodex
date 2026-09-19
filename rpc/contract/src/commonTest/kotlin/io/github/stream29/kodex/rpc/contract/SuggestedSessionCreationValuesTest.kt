package io.github.stream29.kodex.rpc.contract

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import kotlinx.io.files.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val suggestedSessionCreationValuesTest by testSuite {
    test("flattened task list preserves names prompts order and duplicates") {
        val task = SuggestedSubagentTask("Task", "用户输入\n{\"quoted\":\"value\"}\\")
        val tasks = listOf(task, SuggestedSubagentTask("Task", "Different prompt"), task)
        val encoded = Json.encodeToString(tasks)
        assertEquals(tasks, Json.decodeFromString<List<SuggestedSubagentTask>>(encoded))
        assertEquals(setOf("name", "prompt"), Json.parseToJsonElement(encoded).jsonArray[0].jsonObject.keys)
    }

    test("batch settings use the full existing model rather than a frontend projection") {
        val settings = KodexAgentSettings(
            model = OpenAiModelId("fixture-model"),
            cwd = Path("/fixture/project"),
            threadName = "input title",
            reasoning = Reasoning(effort = ReasoningEffort.Low),
            serviceTier = ServiceTier.Default,
            requestUserInputMode = RequestUserInputMode.AskUser,
            autoCompactionTokenLimit = 12345,
            instructions = "Additional settings stay in the original model.",
            parallelToolCalls = true,
            turnId = "input-turn",
            windowNumber = 12,
            firstWindowId = "input-first-window",
            windowId = "input-window",
        )
        // Value transport preserves fields; the backend's per-child initialization is not implemented here.
        assertEquals(settings, Json.decodeFromString<KodexAgentSettings>(Json.encodeToString(settings)))
    }

    test("task value decoding rejects missing fields and nulls without inventing admission rules") {
        // Empty-list encoding does not assert that an empty batch is admitted by a backend.
        assertEquals(emptyList(), Json.decodeFromString<List<SuggestedSubagentTask>>("[]"))
        for (invalid in listOf(
            "null",
            "[null]",
            """[{"name":"Task"}]""",
            """[{"prompt":"Work"}]""",
            """[{"name":null,"prompt":"Work"}]""",
            """{"tasks":[]}""",
        )) {
            assertFailsWith<SerializationException> {
                Json.decodeFromString<List<SuggestedSubagentTask>>(invalid)
            }
        }
    }
}
