package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.assertEquals

private val indexGoldenInputs = listOf(
    """{"type":"event_ask_user","ask_id":1,"arguments":{"questions":[{"id":"q1","header":"Scope","question":"Continue?","isOther":false,"isSecret":false}]},"response":{"answers":{"q1":{"answers":["yes"]}}}}""",
    """{"type":"plan_update","call_id":"plan","arguments":{"explanation":"Current","plan":[{"step":"Verify","status":"in_progress"}]}}""",
    """{"type":"user_message","content":[{"type":"input_text","text":"Synthetic"}]}""",
    """{"type":"context_compaction","encrypted_content":"synthetic","retained_items":[]}""",
)

private val retainedProjectionGoldens = listOf(
    """[{"type":"message","role":"user","content":[{"type":"input_text","text":"ask_user #1 answered.\nquestions: {\"questions\":[{\"id\":\"q1\",\"header\":\"Scope\",\"question\":\"Continue?\",\"isOther\":false,\"isSecret\":false}]}\nanswers: {\"answers\":{\"q1\":{\"answers\":[\"yes\"]}}}"}]}]""",
    """[{"type":"function_call","name":"update_plan","arguments":"{\"explanation\":\"Current\",\"plan\":[{\"step\":\"Verify\",\"status\":\"in_progress\"}]}","call_id":"plan"},{"type":"function_call_output","call_id":"plan","output":"Plan updated"}]""",
    """[{"type":"message","role":"user","content":[{"type":"input_text","text":"Synthetic"}]}]""",
)

val stableTimelineGoldenTest by testSuite {
    test("index and retained serializers agree") {
        var retainedIndex = 0
        for (source in indexGoldenInputs) {
            val event = OpenAiJsonCodec.decodeFromString(IndexCommit.serializer(), source)
            val canonical = OpenAiJsonCodec.encodeToJsonElement(IndexCommit.serializer(), event)
            assertEquals(OpenAiJsonCodec.parseToJsonElement(source), canonical)
            assertEquals(event, OpenAiJsonCodec.decodeFromJsonElement(IndexCommit.serializer(), canonical))
            if (event is CompactionRetainedItem) {
                assertEquals(canonical, OpenAiJsonCodec.encodeToJsonElement(CompactionRetainedItem.serializer(), event))
                val projection = OpenAiJsonCodec.encodeToString(
                    ListSerializer(ResponseItem.HistoryItem.serializer()),
                    event.toResponseHistoryItems(),
                )
                assertEquals(
                    OpenAiJsonCodec.parseToJsonElement(retainedProjectionGoldens[retainedIndex++]),
                    OpenAiJsonCodec.parseToJsonElement(projection),
                )
            }
        }
    }

    test("work timeline serialization") {
        for (source in listOf(
            """{"type":"text_tool_event","call_id":"tool","name":"fixture","arguments":{},"result":"OK","success":true}""",
            """{"type":"read_media_tool_event","call_id":"media","arguments":{"path":"/tmp/image.png","detail":"original"},"result":{"type":"success","output":{"image_url":"data:image/png;base64,aW1hZ2U=","detail":"original"}}}""",
            """{"type":"command_execution_tool_event","call_id":"command","action":{"type":"exec_command","arguments":{"cmd":"echo synthetic","name":"Check synthetic output","yield_time_ms":10000,"max_output_tokens":10000}},"result":{"type":"failure","message":"Synthetic failure"}}""",
            """{"type":"command_execution_tool_event","call_id":"command","action":{"type":"write_stdin","arguments":{"session_id":42,"chars":"","yield_time_ms":250,"max_output_tokens":10000}},"result":{"type":"output","value":{"chunk_id":"chunk","wall_time_seconds":0.5,"exit_code":0,"original_token_count":1,"output":"OK"}}}""",
        )) {
            val event = OpenAiJsonCodec.decodeFromString(WorkCommit.serializer(), source)
            val canonical = OpenAiJsonCodec.encodeToJsonElement(WorkCommit.serializer(), event)
            assertEquals(OpenAiJsonCodec.parseToJsonElement(source), canonical)
            assertEquals(event, OpenAiJsonCodec.decodeFromJsonElement(WorkCommit.serializer(), canonical))
        }
    }
}
