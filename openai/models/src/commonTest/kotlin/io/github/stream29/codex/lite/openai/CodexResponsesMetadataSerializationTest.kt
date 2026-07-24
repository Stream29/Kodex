package io.github.stream29.codex.lite.openai

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse

val codexResponsesMetadataSerializationTest by testSuite {
    test("serializes client metadata through explicit Codex fields") {
        val metadata = CodexResponsesClientMetadata(
            installationId = "installation",
            sessionId = "session",
            threadId = "thread",
            turnId = "turn",
            windowId = "window",
            turnMetadata = """{"request_kind":"turn"}""",
        )

        val encoded = OpenAiJsonCodec
            .encodeToJsonElement(CodexResponsesClientMetadata.serializer(), metadata)
            .jsonObject

        assertEquals(
            JsonObject(
                mapOf(
                    "x-codex-installation-id" to JsonPrimitive("installation"),
                    "session_id" to JsonPrimitive("session"),
                    "thread_id" to JsonPrimitive("thread"),
                    "turn_id" to JsonPrimitive("turn"),
                    "x-codex-window-id" to JsonPrimitive("window"),
                    "x-codex-turn-metadata" to JsonPrimitive("""{"request_kind":"turn"}"""),
                ),
            ),
            encoded,
        )
    }

    test("serializes turn metadata with request kind discriminator") {
        val metadata = CodexResponsesMetadata(
            sessionId = "session",
            threadId = "thread",
            turnId = "turn",
            windowId = "window",
            requestKind = CodexResponsesRequestKind.Turn,
        )

        val encoded = OpenAiJsonCodec
            .encodeToJsonElement(CodexResponsesMetadata.serializer(), metadata)
            .jsonObject

        assertEquals(JsonPrimitive("turn"), encoded["request_kind"])
        assertFalse("installation_id" in encoded)
        assertEquals(JsonPrimitive("session"), encoded["session_id"])
        assertEquals(JsonPrimitive("thread"), encoded["thread_id"])
        assertEquals(JsonPrimitive("turn"), encoded["turn_id"])
        assertEquals(JsonPrimitive("window"), encoded["window_id"])
        assertFalse("compaction" in encoded)
        assertEquals(
            metadata,
            OpenAiJsonCodec.decodeFromJsonElement(CodexResponsesMetadata.serializer(), encoded),
        )
    }

    test("serializes compaction metadata as a valid request-kind branch") {
        val metadata = CodexResponsesMetadata(
            installationId = "installation",
            sessionId = "session",
            threadId = "thread",
            turnId = "turn",
            windowId = "window",
            requestKind = CodexResponsesRequestKind.Compaction(
                metadata = CompactionTurnMetadata(
                    trigger = CompactionTrigger.Manual,
                    reason = CompactionReason.UserRequested,
                    implementation = CompactionImplementation.ResponsesCompactionV2,
                    phase = CompactionPhase.StandaloneTurn,
                    strategy = CompactionStrategy.Memento,
                ),
            ),
        )

        val encoded = OpenAiJsonCodec
            .encodeToJsonElement(CodexResponsesMetadata.serializer(), metadata)
            .jsonObject
        val compaction = encoded.getValue("compaction").jsonObject

        assertEquals(JsonPrimitive("compaction"), encoded["request_kind"])
        assertEquals(JsonPrimitive("manual"), compaction["trigger"])
        assertEquals(JsonPrimitive("user_requested"), compaction["reason"])
        assertEquals(JsonPrimitive("responses_compaction_v2"), compaction["implementation"])
        assertEquals(JsonPrimitive("standalone_turn"), compaction["phase"])
        assertEquals(JsonPrimitive("memento"), compaction["strategy"])
        assertEquals(
            metadata,
            OpenAiJsonCodec.decodeFromJsonElement(CodexResponsesMetadata.serializer(), encoded),
        )
    }

    test("omits turn identity from detached memory metadata") {
        val metadata = CodexResponsesMetadata(
            installationId = "unused-installation",
            sessionId = "unused-session",
            threadId = "unused-thread",
            turnId = "unused-turn",
            windowId = "unused-window",
            requestKind = CodexResponsesRequestKind.Memory,
        )

        val encoded = OpenAiJsonCodec
            .encodeToJsonElement(CodexResponsesMetadata.serializer(), metadata)
            .jsonObject

        assertEquals(
            JsonObject(mapOf("request_kind" to JsonPrimitive("memory"))),
            encoded,
        )
        assertEquals(
            CodexResponsesRequestKind.Memory,
            OpenAiJsonCodec
                .decodeFromJsonElement(CodexResponsesMetadata.serializer(), encoded)
                .requestKind,
        )
    }
}
