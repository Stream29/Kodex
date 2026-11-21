package io.github.stream29.kodex.tool.awaitevent

import io.github.stream29.kodex.tool.intentname.isValidIntentName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toDuration

/** Any listed event can finish the wait. Existing direct user, ask and schedule wakes also participate. */
@Serializable
public data class AwaitEventArguments(
    public val events: List<AwaitEventInput>,
    public val name: String,
) {
    init {
        require(events.isNotEmpty()) { "await_event requires at least one event." }
        require(name.isValidIntentName()) { "`name` must be nonblank, single-line text." }
    }

    public val terminalIds: Set<Int>
        get() = events.filterIsInstance<AwaitEventInput.TerminalExited>().mapTo(linkedSetOf()) { it.sessionId }

    public val timeoutDelay: Duration?
        get() = events.filterIsInstance<AwaitEventInput.Timeout>()
            .minOfOrNull { it.durationMillis }
            ?.toDuration(DurationUnit.MILLISECONDS)

    /** Anchors the shortest timeout to the timestamp of the initial pending commit. */
    public fun timeoutAt(timestamp: Instant): Instant? = timeoutDelay?.let { delay ->
        val deadline = timestamp + delay
        require(deadline > timestamp && deadline - timestamp == delay) {
            "await_event timeout cannot be represented as a deadline."
        }
        deadline
    }
}

@Serializable
public sealed interface AwaitEventInput {
    @Serializable
    @SerialName("user_message")
    public data object UserMessage : AwaitEventInput

    @Serializable
    @SerialName("ask_user_answered")
    public data object AskUserAnswered : AwaitEventInput

    @Serializable
    @SerialName("schedule_triggered")
    public data object ScheduleTriggered : AwaitEventInput

    @Serializable
    @SerialName("terminal_exited")
    public data class TerminalExited(
        @SerialName("session_id") public val sessionId: Int,
    ) : AwaitEventInput

    @Serializable
    @SerialName("timeout")
    public data class Timeout(
        @SerialName("duration_ms") public val durationMillis: Long,
    ) : AwaitEventInput {
        init {
            val duration = durationMillis.toDuration(DurationUnit.MILLISECONDS)
            require(durationMillis > 0 && duration.isFinite() && duration.inWholeMilliseconds == durationMillis) {
                "await_event duration_ms must be a representable positive integer."
            }
        }
    }
}

/** Parses a new model call, not a historical record. Unknown fields and quoted integers are invalid. */
public fun decodeAwaitEventArguments(input: String): AwaitEventArguments {
    val json = Json.parseToJsonElement(input)
    val fields = json as? JsonObject
        ?: throw IllegalArgumentException("await_event requires an arguments object.")
    val name = fields["name"] as? JsonPrimitive
    require(name?.isString == true && name.content.isValidIntentName()) {
        "`name` must be nonblank, single-line text."
    }
    val events = fields["events"] as? JsonArray
        ?: throw IllegalArgumentException("await_event requires an events array.")
    for (event in events) {
        val eventFields = event as? JsonObject
            ?: throw IllegalArgumentException("await_event events must be objects.")
        val tag = eventFields["type"] as? JsonPrimitive
        require(tag?.isString == true) { "await_event event type must be a string." }
        val integerField = when (tag.content) {
            "terminal_exited" -> "session_id"
            "timeout" -> "duration_ms"
            else -> continue
        }
        val value = eventFields[integerField] as? JsonPrimitive
        require(value != null && !value.isString && value.content.all { it in '0'..'9' }) {
            "await_event $integerField must be a positive JSON integer."
        }
        if (integerField == "session_id") {
            require(value.content.toIntOrNull()?.let { it > 0 } == true) {
                "await_event session_id must be a positive Int."
            }
        }
    }
    return Json.decodeFromJsonElement(AwaitEventArguments.serializer(), json)
}

/**
 * Output returned by `await_event` when the Agent is woken.
 *
 * All wake payloads (direct user message, answered `ask_user` interaction,
 * fired schedule, or watched terminal exit) are delivered as dedicated
 * steerable commits via `steer` and consumed immediately after `await_event`
 * completes. A timeout sends no steer. The tool output only records the wake [timestamp].
 */
@Serializable
public data class AwaitEventOutput(
    public val timestamp: Instant,
)
