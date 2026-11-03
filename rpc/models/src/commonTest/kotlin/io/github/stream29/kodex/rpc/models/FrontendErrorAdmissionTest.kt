package io.github.stream29.kodex.rpc.models

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val frontendErrorAdmissionTest by testSuite {
    test("admission has only the two stable wire values") {
        val cases = mapOf(
            FrontendErrorAdmission.Admitted to "\"admitted\"",
            FrontendErrorAdmission.RejectedOverload to "\"rejected_overload\"",
        )
        assertEquals(FrontendErrorAdmission.entries.toSet(), cases.keys)
        cases.forEach { (value, encoded) ->
            assertEquals(encoded, Json.encodeToString(FrontendErrorAdmission.serializer(), value))
            assertEquals(value, Json.decodeFromString(FrontendErrorAdmission.serializer(), encoded))
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString(FrontendErrorAdmission.serializer(), "\"unknown\"")
        }
    }
}
