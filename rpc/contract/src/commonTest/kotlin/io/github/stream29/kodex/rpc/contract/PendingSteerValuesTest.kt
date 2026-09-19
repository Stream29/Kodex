package io.github.stream29.kodex.rpc.contract

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val pendingSteerValuesTest by testSuite {
    val serializer = ListSerializer(StableIndexEvent.Steerable.serializer())
    val user = StableUserMessage(listOf(ContentItem.InputText("用户补充\nnext")))

    test("all existing steer variants preserve fields order and duplicates") {
        val queue = listOf(
            user,
            StableAssistantMessage(listOf(ContentItem.OutputText("Assistant"))),
            StableDeveloperMessage(listOf(ContentItem.InputText("Instruction"))),
            StableAgentMessage("sender", "receiver", listOf(AgentMessageInputContent.InputText("Hello"))),
            user,
        )
        assertEquals(queue, Json.decodeFromString(serializer, Json.encodeToString(serializer, queue)))
    }

    test("an empty queue is a value and not an absent snapshot") {
        assertEquals("[]", Json.encodeToString(serializer, emptyList()))
        assertEquals(emptyList(), Json.decodeFromString(serializer, "[]"))
        assertFailsWith<SerializationException> {
            Json.decodeFromString(serializer, "null")
        }
    }

    test("unknown steer types are rejected rather than silently dropped") {
        assertFailsWith<SerializationException> {
            Json.decodeFromString(serializer, """[{"type":"unknown_steer"}]""")
        }
    }
}
