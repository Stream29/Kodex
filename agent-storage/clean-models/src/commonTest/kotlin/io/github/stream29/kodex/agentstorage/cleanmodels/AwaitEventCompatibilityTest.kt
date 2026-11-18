package io.github.stream29.kodex.agentstorage.cleanmodels

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwait
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwaitEventResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.UnstableSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.decodePayloadJson
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwait
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.tool.awaitevent.AwaitEventInput
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

val awaitEventCompatibilityTest by testSuite {
    test("legacy pending and stable arguments decode without synthesizing a timeout") {
        val external = listOf(AwaitEventInput.UserMessage, AwaitEventInput.AskUserAnswered, AwaitEventInput.ScheduleTriggered)
        val cases = mapOf(
            "{}" to external,
            """{"terminals":[]}""" to external,
            """{"terminals":[7,9]}""" to listOf(AwaitEventInput.TerminalExited(7), AwaitEventInput.TerminalExited(9)),
        )
        for ((args, expected) in cases) {
            val stable = assertIs<StableAwait>(AgentCommitType.AwaitEventToolEvent.decodePayloadJson(
                """{"type":"await_event_tool_event","call_id":"fixture","arguments":$args,"result":{"type":"output","value":{"timestamp":"2026-11-17T00:00:00Z"}}}""",
            ))
            val pending = assertIs<PendingAwait>(OpenAiJsonCodec.decodeFromString(
                PendingAwait.serializer(),
                """{"call_id":"fixture","arguments":$args}""",
            ))
            assertEquals(expected, stable.arguments.events)
            assertEquals(expected, pending.arguments.events)
            assertEquals(Instant.parse("2026-11-17T00:00:00Z"), assertIs<StableAwaitEventResult.Output>(stable.result).value.timestamp)
            assertNull(pending.timeoutAt)
            assertNull(pending.armedForAgentId)
            val providerArgs = assertIs<ResponseItem.FunctionCall>(pending.toResponseHistoryItems().single()).arguments
            val fields = OpenAiJsonCodec.parseToJsonElement(providerArgs).jsonObject
            assertEquals(setOf("events"), fields.keys)
        }
    }

    test("persisted deadline and arming round-trip without reaching model arguments") {
        val encoded = """[{"type":"await_event","call_id":"fixture","arguments":{"events":[{"type":"timeout","duration_ms":1000}]},"timeout_at":"2026-11-17T00:00:01Z","armed_for_agent_id":4}]"""
        val snapshot = assertIs<UnstableSnapshot>(AgentCommitType.Unstable.decodePayloadJson(encoded))
        val pending = assertIs<PendingAwait>(snapshot.events.single())
        assertEquals(Instant.parse("2026-11-17T00:00:01Z"), pending.timeoutAt)
        assertEquals(4, pending.armedForAgentId)
        val providerArgs = assertIs<ResponseItem.FunctionCall>(pending.toResponseHistoryItems().single()).arguments
        assertFalse("timeout_at" in providerArgs)
        assertFalse("armed_for_agent_id" in providerArgs)
        assertFailsWith<IllegalArgumentException> {
            AgentCommitType.Unstable.decodePayloadJson(encoded.replace(""","timeout_at":"2026-11-17T00:00:01Z","armed_for_agent_id":4""", ""))
        }
    }
}
