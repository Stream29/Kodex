package io.github.stream29.kodex.utils.logging

import de.infix.testBalloon.framework.core.testSuite
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KLoggingEventBuilder
import io.github.oshai.kotlinlogging.Level
import io.github.oshai.kotlinlogging.Marker
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

val scopedLoggingTest by testSuite {
    test("derives inherited context payload for every scope") {
        val factory = RecordingLoggerFactory()
        val global = factory.logger("runtime").global()
        val session = global.session("session-1")
        val agent = session.agent("agent-2")
        val tool = agent.tool("unified_exec", "call-3")

        global.info { "global event" }
        session.info { "session event" }
        agent.info { "agent event" }
        tool.info { "tool event" }

        assertEquals(
            listOf(
                RecordedEvent(
                    loggerName = "runtime",
                    message = "global event",
                    payload = mapOf("scope" to "global"),
                ),
                RecordedEvent(
                    loggerName = "runtime",
                    message = "session event",
                    payload = mapOf(
                        "scope" to "session",
                        "session_id" to "session-1",
                    ),
                ),
                RecordedEvent(
                    loggerName = "runtime",
                    message = "agent event",
                    payload = mapOf(
                        "scope" to "agent",
                        "session_id" to "session-1",
                        "agent_id" to "agent-2",
                    ),
                ),
                RecordedEvent(
                    loggerName = "runtime",
                    message = "tool event",
                    payload = mapOf(
                        "scope" to "tool",
                        "session_id" to "session-1",
                        "agent_id" to "agent-2",
                        "tool_name" to "unified_exec",
                        "call_id" to "call-3",
                    ),
                ),
            ),
            factory.events.map(KLoggingEvent::recorded),
        )
    }

    test("applies context payload before allowing the event block to replace it") {
        val factory = RecordingLoggerFactory()
        val tool = factory.logger("runtime")
            .global()
            .session("session-1")
            .agent("agent-2")
            .tool("apply_patch", "call-3")

        tool.info { "first tool event" }
        tool.info { "second tool event" }

        val first = factory.events[0]
        val second = factory.events[1]
        assertSame(first.payload, second.payload)

        val eventPayload = linkedMapOf<String, Any?>(
            "agent_id" to "incorrect-agent",
            "attempt" to 2,
        )
        var initialPayload: Map<String, Any?>? = null
        tool.atInfo {
            message = "third tool event"
            initialPayload = payload
            payload = eventPayload
        }

        assertSame(first.payload, initialPayload)
        assertSame(eventPayload, factory.events[2].payload)
    }

    test("preserves an event marker") {
        val factory = RecordingLoggerFactory()
        val logger = factory.logger("runtime").global()
        val marker = TestMarker("transport")

        logger.info(marker) { "connection closed" }

        assertSame(marker, factory.events.single().marker)
    }

    test("keeps message evaluation lazy when a level is disabled") {
        val factory = RecordingLoggerFactory(enabled = { level -> level >= Level.INFO })
        val logger = factory.logger("runtime").global()
        var evaluated = false

        logger.debug {
            evaluated = true
            "debug event"
        }

        assertFalse(evaluated)
        assertEquals(emptyList(), factory.events)
    }
}

private class RecordingLoggerFactory(
    private val enabled: (Level) -> Boolean = { true },
) {
    val events: MutableList<KLoggingEvent> = mutableListOf()

    fun logger(name: String): KLogger =
        RecordingLogger(
            name = name,
            enabled = enabled,
            events = events,
        )
}

private class RecordingLogger(
    override val name: String,
    private val enabled: (Level) -> Boolean,
    private val events: MutableList<KLoggingEvent>,
) : KLogger {
    override fun isLoggingEnabledFor(level: Level, marker: Marker?): Boolean = enabled(level)

    override fun at(
        level: Level,
        marker: Marker?,
        block: KLoggingEventBuilder.() -> Unit,
    ) {
        if (!isLoggingEnabledFor(level, marker)) return
        events += KLoggingEvent(
            level = level,
            marker = marker,
            loggerName = name,
            eventBuilder = KLoggingEventBuilder().apply(block),
        )
    }
}

private data class RecordedEvent(
    val loggerName: String,
    val message: String?,
    val payload: Map<String, Any?>?,
)

private fun KLoggingEvent.recorded(): RecordedEvent =
    RecordedEvent(
        loggerName = loggerName,
        message = message,
        payload = payload,
    )

private class TestMarker(
    private val name: String,
) : Marker {
    override fun getName(): String = name
}
