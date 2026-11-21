package io.github.stream29.kodex.agentstorage.cleanmodels

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwait
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.UnstableSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.decodePayloadJson
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwait
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Instant

val awaitEventCompatibilityTest by testSuite {
    test("old pending and stable await arguments require migration before decoding") {
        val legacy = listOf(
            "{}",
            """{"terminals":[]}""",
            """{"terminals":[7,9]}""",
            """{"events":[{"type":"user_message"}]}""",
            """{"name":null,"events":[{"type":"user_message"}]}""",
        )
        for (args in legacy) {
            assertFailsWith<IllegalArgumentException> {
                AgentCommitType.AwaitEventToolEvent.decodePayloadJson(
                    """{"type":"await_event_tool_event","call_id":"fixture","arguments":$args,"result":{"type":"failure","message":"fixture"}}""",
                )
            }
            assertFailsWith<IllegalArgumentException> {
                OpenAiJsonCodec.decodeFromString(PendingAwait.serializer(), """{"call_id":"fixture","arguments":$args}""")
            }
        }
        val compatible = """{"name":"Wait for input","events":[{"type":"user_message"}]}"""
        val stable = assertIs<StableAwait>(AgentCommitType.AwaitEventToolEvent.decodePayloadJson(
            """{"type":"await_event_tool_event","call_id":"fixture","arguments":$compatible,"result":{"type":"failure","message":"fixture"}}""",
        ))
        assertEquals("Wait for input", stable.arguments.name)
    }

    test("persisted deadline and arming round-trip without reaching model arguments") {
        val encoded = """[{"type":"await_event","call_id":"fixture","arguments":{"name":"Wait for deadline","events":[{"type":"timeout","duration_ms":1000}]},"timeout_at":"2026-11-17T00:00:01Z","armed_for_agent_id":4}]"""
        val snapshot = assertIs<UnstableSnapshot>(AgentCommitType.Unstable.decodePayloadJson(encoded))
        val pending = assertIs<PendingAwait>(snapshot.events.single())
        assertEquals(Instant.parse("2026-11-17T00:00:01Z"), pending.timeoutAt)
        assertEquals(4, pending.armedForAgentId)
        val providerArgs = assertIs<ResponseItem.FunctionCall>(pending.toResponseHistoryItems().single()).arguments
        assertEquals(setOf("name", "events"), OpenAiJsonCodec.parseToJsonElement(providerArgs).jsonObject.keys)
        assertFalse("timeout_at" in providerArgs)
        assertFalse("armed_for_agent_id" in providerArgs)
        assertFailsWith<IllegalArgumentException> {
            AgentCommitType.Unstable.decodePayloadJson(encoded.replace(""","timeout_at":"2026-11-17T00:00:01Z","armed_for_agent_id":4""", ""))
        }
    }
}
