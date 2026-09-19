package io.github.stream29.kodex.rpc.contract

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val usageResetValuesTest by testSuite {
    test("all definitive reset outcomes round trip without an attempt envelope") {
        for (outcome in CodexRateLimitResetOutcome.entries) {
            val encoded = Json.encodeToString(CodexRateLimitResetOutcome.serializer(), outcome)
            assertEquals("\"${outcome.name}\"", encoded)
            assertEquals(
                outcome,
                Json.decodeFromString(CodexRateLimitResetOutcome.serializer(), encoded),
            )
        }
    }

    test("unknown or null reset outcomes are not successful business results") {
        for (encoded in listOf("\"Unknown\"", "null")) {
            assertFailsWith<SerializationException> {
                Json.decodeFromString(CodexRateLimitResetOutcome.serializer(), encoded)
            }
        }
    }
}
