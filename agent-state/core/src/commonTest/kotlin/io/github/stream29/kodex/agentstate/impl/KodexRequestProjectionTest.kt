package io.github.stream29.kodex.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

val kodexRequestProjectionTest by testSuite {
    test("default cache key follows thread only and overrides remain untouched") {
        for (override in listOf(null, "", " ", "explicit")) {
            val settings = KodexAgentSettings(OpenAiModelId("test"), promptCacheKey = override)
            for (thread in listOf("thread-a", "thread-b")) {
                for (window in listOf("0", "1")) {
                    for (kind in listOf("turn", "compaction")) {
                        val metadata = CodexResponsesClientMetadata(
                            threadId = thread, turnId = "turn-$window",
                            windowId = "$thread:$window", turnMetadata = kind,
                        )
                        val request = settings.toResponsesApiRequest(emptyList(), metadata, emptyList())
                        val encoded = OpenAiJsonCodec.encodeToJsonElement(ResponsesApiRequest.serializer(), request).jsonObject
                        assertEquals(override ?: thread, request.promptCacheKey)
                        assertEquals(JsonPrimitive(override ?: thread), encoded["prompt_cache_key"])
                        assertEquals(override, settings.promptCacheKey)
                    }
                }
            }
        }
    }
    test("storage identity projects to a stable provider thread id") {
        val first = "filesystem:/tmp/kodex/session-1".toCodexThreadId()

        assertEquals(first, "filesystem:/tmp/kodex/session-1".toCodexThreadId())
        assertNotEquals(first, "filesystem:/tmp/kodex/session-2".toCodexThreadId())
        assertEquals(first, Uuid.parse(first).toString())
    }
}
