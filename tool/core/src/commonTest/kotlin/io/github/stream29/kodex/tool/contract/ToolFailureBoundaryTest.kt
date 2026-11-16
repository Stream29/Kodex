package io.github.stream29.kodex.tool.contract

import de.infix.testBalloon.framework.core.testSuite
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

private val testSpec = ResponsesApiTool(
    name = "test",
    description = "Test tool.",
    parameters = PropertyBuilder().obj { additionalProperties = false },
)

val toolFailureBoundaryTest by testSuite {
    test("handler exception propagates to runtime caller") {
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

        val failure = assertFailsWith<IllegalStateException> { tool.handle(pending) }
        assertEquals("private-marker", failure.message)
    }

    test("selector mismatch propagates IllegalArgumentException to runtime caller") {
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

        assertFailsWith<IllegalArgumentException> { tool.handle(pending) }
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
