package io.github.stream29.kodex.rpc.models

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals

val shellSessionStateIntentTest by testSuite {
    test("terminal registration preserves the original nullable argument on the wire") {
        for ((field, name) in listOf(
            "" to null, ",\"name\":null" to null, ",\"name\":\"\"" to "",
            ",\"name\":\" 检查\\n日志 🧪 \"" to " 检查\n日志 🧪 ",
        )) {
            val state = Json.decodeFromString<ShellSessionState>(
                """{"arguments":{"cmd":"pwd","yield_time_ms":10$field},"registrationNonce":"fixture"}""",
            )
            assertEquals(name, state.arguments.name)
            assertEquals("fixture", state.registrationNonce)
            assertEquals(state, Json.decodeFromString<ShellSessionState>(Json.encodeToString(ShellSessionState.serializer(), state)))
        }
    }
}
