package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.applypatch.parsePatch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals

val stablePatchWireTest by testSuite {
    test("patch event preserves structured hunks without a host filesystem") {
        val event = StablePatchToolEvent(
            callId = "call_browser_patch",
            diff = """
                *** Begin Patch
                *** Add File: 你好.txt
                +shared wire
                *** End Patch
            """.trimIndent().parsePatch(),
            result = StablePatchToolExecutionResult.Failure("fixture"),
        )
        val encoded = Json.encodeToString(event)
        assertEquals(event, Json.decodeFromString<StablePatchToolEvent>(encoded))
        assertEquals(event.toResponseHistoryItems(), Json.decodeFromString<StablePatchToolEvent>(encoded).toResponseHistoryItems())
    }
}
