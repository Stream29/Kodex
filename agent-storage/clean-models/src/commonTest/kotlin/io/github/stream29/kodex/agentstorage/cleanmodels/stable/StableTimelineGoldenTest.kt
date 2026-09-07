package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlin.test.assertEquals

// Synthetic pre-migration fixtures: never copied from a user's Session.
internal val suggestionGoldenInputs = listOf(
    """{"type":"suggest_subagent_task_tool_event","call_id":"suggest","arguments":{"tasks":[{"name":"Worker","prompt":"Inspect tests."}]},"result":{"type":"completed","response":{"type":"accepted","decision":"accepted","sessions":[{"uri":"file:///tmp/synthetic/1","name":"Worker"}]}}}""",
    """{"type":"suggest_subagent_task_tool_event","call_id":"suggest","arguments":{"tasks":[{"name":"Worker","prompt":"Inspect tests."}]},"result":{"type":"completed","response":{"type":"rejected","decision":"rejected","feedback":"Narrow the scope."}}}""",
    """{"type":"suggest_subagent_task_tool_event","call_id":"suggest","arguments":{"tasks":[{"name":"Worker","prompt":"Inspect tests."}]},"result":{"type":"failure","message":"Stopped by hook."}}""",
)

private val retainedProjectionGoldens = listOf(
    """[{"type":"function_call","name":"suggest_subagent_task","arguments":"{\"tasks\":[{\"name\":\"Worker\",\"prompt\":\"Inspect tests.\"}]}","call_id":"suggest"},{"type":"function_call_output","call_id":"suggest","output":"{\"feedback\":null,\"sessions\":[{\"uri\":\"file:///tmp/synthetic/1\",\"name\":\"Worker\"}],\"decision\":\"accepted\"}"}]""",
    """[{"type":"function_call","name":"suggest_subagent_task","arguments":"{\"tasks\":[{\"name\":\"Worker\",\"prompt\":\"Inspect tests.\"}]}","call_id":"suggest"},{"type":"function_call_output","call_id":"suggest","output":"{\"feedback\":\"Narrow the scope.\",\"decision\":\"rejected\"}"}]""",
    """[{"type":"function_call","name":"suggest_subagent_task","arguments":"{\"tasks\":[{\"name\":\"Worker\",\"prompt\":\"Inspect tests.\"}]}","call_id":"suggest"},{"type":"function_call_output","call_id":"suggest","output":"Stopped by hook."}]""",
    """[{"type":"message","role":"user","content":[{"type":"input_text","text":"Synthetic"}]}]""",
)

val stableTimelineGoldenTest by testSuite {
    test("pre-migration index and retained serializers agree") {
        var retainedIndex = 0
        for (source in suggestionGoldenInputs + listOf(
            """{"type":"user_message","content":[{"type":"input_text","text":"Synthetic"}]}""",
            """{"type":"compaction_point"}""",
        )) {
            val event = OpenAiJsonCodec.decodeFromString(CleanIndexEntry.serializer(), source)
            val canonical = OpenAiJsonCodec.encodeToJsonElement(CleanIndexEntry.serializer(), event)
            assertEquals(OpenAiJsonCodec.parseToJsonElement(source), canonical)
            assertEquals(event, OpenAiJsonCodec.decodeFromJsonElement(CleanIndexEntry.serializer(), canonical))
            if (event is CompactionRetainedItem) {
                assertEquals(canonical, OpenAiJsonCodec.encodeToJsonElement(CompactionRetainedItem.serializer(), event))
                val projection = OpenAiJsonCodec.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(io.github.stream29.kodex.openai.ResponseItem.HistoryItem.serializer()),
                    event.toResponseHistoryItems(),
                )
                assertEquals(
                    OpenAiJsonCodec.parseToJsonElement(retainedProjectionGoldens[retainedIndex++]),
                    OpenAiJsonCodec.parseToJsonElement(projection),
                )
            }
        }
    }
    test("pre-migration work serialization") {
        // Command goldens also verified against pre-migration commit 8d4cfba7.
        for (source in listOf(
            """{"type":"text_tool_event","call_id":"tool","name":"fixture","arguments":{},"result":"OK","success":true}""",
            """{"type":"context_compaction","encrypted_content":"synthetic"}""",
            """{"type":"command_execution_tool_event","call_id":"command","action":{"type":"exec_command","arguments":{"cmd":"echo synthetic","tty":false,"yield_time_ms":10000,"max_output_tokens":10000}},"result":{"type":"failure","message":"Synthetic failure"}}""",
            """{"type":"command_execution_tool_event","call_id":"command","action":{"type":"write_stdin","arguments":{"session_id":42,"chars":"","yield_time_ms":250,"max_output_tokens":10000}},"result":{"type":"output","value":{"chunk_id":"chunk","wall_time_seconds":0.5,"exit_code":0,"original_token_count":1,"output":"OK"}}}""",
        )) {
            val event = OpenAiJsonCodec.decodeFromString(StableWorkEvent.serializer(), source)
            val canonical = OpenAiJsonCodec.encodeToJsonElement(StableWorkEvent.serializer(), event)
            assertEquals(OpenAiJsonCodec.parseToJsonElement(source), canonical)
            assertEquals(event, OpenAiJsonCodec.decodeFromJsonElement(StableWorkEvent.serializer(), canonical))
        }
    }
}
