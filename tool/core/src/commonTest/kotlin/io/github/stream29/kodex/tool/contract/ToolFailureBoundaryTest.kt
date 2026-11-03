package io.github.stream29.kodex.tool.contract

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.openai.ResponsesApiTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.schema.json.PropertyBuilder
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

private val testSpec = ResponsesApiTool(
    name = "test",
    description = "Test tool.",
    parameters = PropertyBuilder().obj { additionalProperties = false },
)

val toolFailureBoundaryTest by testSuite {
    test("handler exception produces a sanitized completion with the original identity") {
        val tool = typedTool(
            spec = testSpec,
            select = { it as? PendingFunctionToolEvent },
        ) { error("private-marker") }
        val pending = PendingFunctionToolEvent(
            name = "test",
            namespace = "local",
            arguments = JsonObject(emptyMap()),
            callId = "call-one",
            itemId = ResponseItemId("item-one"),
        )

        val event = assertIs<StableTextToolEvent>(tool.handle(pending))
        assertEquals(pending.callId, event.callId)
        assertEquals(pending.itemId, event.itemId)
        assertEquals(pending.name, event.name)
        assertEquals(pending.namespace, event.namespace)
        assertEquals(pending.arguments, event.arguments)
        assertEquals(false, event.success)
        assertEquals("Tool execution failed.", event.result)
    }

    test("selector mismatch becomes the matching custom failure") {
        val tool = typedTool(
            spec = testSpec,
            select = { it as? PendingFunctionToolEvent },
        ) { error("must not run") }
        val pending = PendingCustomToolEvent(
            name = "test",
            input = "input",
            callId = "call-two",
            itemId = ResponseItemId("item-two"),
        )

        val event = assertIs<StableCustomToolEvent>(tool.handle(pending))
        assertEquals(pending.callId, event.callId)
        assertEquals(pending.itemId, event.itemId)
        assertEquals(pending.input, event.input)
        assertEquals(false, event.success)
    }

    test("cancellation is propagated instead of converted") {
        val cancellation = CancellationException("cancel test")
        val tool = typedTool(
            spec = testSpec,
            select = { it as? PendingFunctionToolEvent },
        ) { throw cancellation }
        val pending = PendingFunctionToolEvent(
            name = "test",
            arguments = JsonObject(emptyMap()),
            callId = "call-three",
        )

        assertEquals(cancellation, assertFailsWith<CancellationException> { tool.handle(pending) })
    }

    test("concurrent cancellation is not hidden by a different handler exception") {
        val tool = typedTool(
            spec = testSpec,
            select = { it as? PendingFunctionToolEvent },
        ) {
            currentCoroutineContext().cancel()
            error("exception after cancellation")
        }
        val pending = PendingFunctionToolEvent(
            name = "test",
            arguments = JsonObject(emptyMap()),
            callId = "call-four",
        )
        coroutineScope {
            val call = async { tool.handle(pending) }
            assertFailsWith<CancellationException> { call.await() }
        }
    }

    test("a normal handler return cannot hide an already cancelled invocation") {
        val tool = typedTool(
            spec = testSpec,
            select = { it as? PendingFunctionToolEvent },
        ) { pending ->
            currentCoroutineContext().cancel()
            StableTextToolEvent(
                callId = pending.callId,
                name = pending.name,
                arguments = pending.arguments,
                result = "not committed",
                success = true,
            )
        }
        val pending = PendingFunctionToolEvent(
            name = "test",
            arguments = JsonObject(emptyMap()),
            callId = "call-five",
        )
        coroutineScope {
            val call = async { tool.handle(pending) }
            assertFailsWith<CancellationException> { call.await() }
        }
    }
}
