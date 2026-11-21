package io.github.stream29.kodex.openai.client

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.contract.ObservedResponseCompletion
import io.github.stream29.kodex.openai.client.contract.withReadObservations
import io.github.stream29.kodex.utils.ktorclientext.SseCompatibility
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

val openAiResponseReadTimingTest by testSuite {
    test("the public observed view and wire view select the same cold request path") {
        withSseAttempts(frame("created") + frame("completed", usage = fullUsage(4)), frame("completed")) {
            val wireFlow = streamResponseEvents(1000, OpenAiClientRetryConfig()) {
                url("https://example.test/responses")
            }
            val observed = wireFlow.withReadObservations().toList()
            assertEquals(4L, observed.last().completion?.reportedOutputTokens)
            assertIs<ResponsesStreamEvent.Completed>(wireFlow.toList().single())
        }
    }

    test("the read clock measures buffered SSE frames before the collector can resume") {
        val terminalRead = CompletableDeferred<Unit>()
        val clock = FrameClock { if (it == 4) terminalRead.complete(Unit) }
        withSseAttempts(
            frame("created") + frame("created") + frame("in_progress") + frame("completed", usage = fullUsage(9)),
        ) {
            val completions = mutableListOf<ObservedResponseCompletion>()
            readObserved(clock).collect { observed ->
                if (observed.event is ResponsesStreamEvent.Created) {
                    // The producer must be able to mark the terminal frame while this collector is suspended.
                    withTimeout(5.seconds) { terminalRead.await() }
                    clock.advance(10.seconds)
                }
                observed.completion?.let { completions += it }
            }
            assertEquals(300.microseconds, completions.single().elapsed)
            assertEquals(9L, completions.single().reportedOutputTokens)
        }
    }

    test("missing usage, partial usage, and a reported zero remain distinct") {
        withSseAttempts(
            frame("created") + frame("completed"),
            frame("created") + frame("completed", usage = """{"input_tokens":100,"total_tokens":140}"""),
            frame("created") + frame("completed", usage = fullUsage(0)),
        ) {
            val clock = FrameClock()
            val flow = readObserved(clock)
            val missing = flow.toList().last()
            val partial = flow.toList().last()
            val zero = flow.toList().last()
            assertNull(assertIs<ResponsesStreamEvent.Completed>(missing.event).response.usage)
            assertEquals(ObservedResponseCompletion(100.microseconds, null, null), missing.completion)
            assertNull(assertIs<ResponsesStreamEvent.Completed>(partial.event).response.usage)
            assertEquals(ObservedResponseCompletion(100.microseconds, null, 140L), partial.completion)
            assertEquals(0L, assertIs<ResponsesStreamEvent.Completed>(zero.event).response.usage?.outputTokens)
            assertEquals(ObservedResponseCompletion(100.microseconds, 0L, 100L), zero.completion)
        }
    }

    test("a cancelled attempt does not provide a start to a new collection even with the same response ID") {
        withSseAttempts(
            frame("created") + frame("completed"),
            frame("completed"),
            frame("created") + frame("completed"),
        ) {
            val flow = readObserved(FrameClock())
            val cancelled = flow.take(1).toList().single()
            assertIs<ResponsesStreamEvent.Created>(cancelled.event)
            assertNull(cancelled.completion)
            assertNull(assertNotNull(flow.toList().single().completion).elapsed)
            assertEquals(100.microseconds, flow.toList().last().completion?.elapsed)
        }
    }

    test("mismatched IDs and timestamps without positive elapsed time do not produce a duration") {
        withSseAttempts(
            frame("created", id = "one") + frame("completed", id = "two"),
            frame("created") + frame("completed"),
        ) {
            val mismatched = readObserved(FrameClock()).toList().last()
            val sameTime = readObserved(FrameClock(step = Duration.ZERO)).toList().last()
            assertNull(assertNotNull(mismatched.completion).elapsed)
            assertNull(assertNotNull(sameTime.completion).elapsed)
        }
    }

    test("a failed or incomplete attempt cannot yield a subsequent measurement on that stream") {
        withSseAttempts(
            frame("created") + frame("failed") + frame("completed"),
            frame("created") + frame("incomplete") + frame("completed"),
            frame("created") + "data: [DONE]\n\n" + frame("completed"),
        ) {
            val flow = readObserved(FrameClock())
            repeat(3) {
                val events = flow.toList()
                assertIs<ResponsesStreamEvent.Completed>(events.last().event)
                assertEquals(emptyList(), events.mapNotNull { it.completion })
            }
        }
    }
}

private class FrameClock(
    private val step: Duration = 100.microseconds,
    private val onRead: (Int) -> Unit = {},
) : TimeSource.WithComparableMarks {
    private val time = TestTimeSource()
    private var reads = 0

    override fun markNow(): ComparableTimeMark {
        val mark = time.markNow()
        time += step
        onRead(++reads)
        return mark
    }

    fun advance(duration: Duration) {
        time += duration
    }
}

private fun frame(kind: String, id: String = "r", usage: String? = null): String {
    val usageProperty = usage?.let { ""","usage":$it""" }.orEmpty()
    return """data: {"type":"response.$kind","response":{"id":"$id"$usageProperty}}""" + "\n\n"
}

private fun fullUsage(outputTokens: Long): String =
    """{"input_tokens":100,"output_tokens":$outputTokens,"total_tokens":${100 + outputTokens}}"""

private fun HttpClient.readObserved(clock: TimeSource.WithComparableMarks) =
    streamObservedResponseEvents(1000, OpenAiClientRetryConfig(), clock = clock) {
        url("https://example.test/responses")
    }

private suspend fun withSseAttempts(vararg bodies: String, block: suspend HttpClient.() -> Unit) {
    var attempt = 0
    HttpClient(SseMockEngine {
        respond(bodies[attempt++], headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }) {
        install(SSE)
        install(SseCompatibility)
    }.use { it.block() }
}
