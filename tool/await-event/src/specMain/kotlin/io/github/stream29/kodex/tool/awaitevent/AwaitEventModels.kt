package io.github.stream29.kodex.tool.awaitevent

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Arguments emitted by the model for `await_event`.
 *
 * @property terminals Optional background terminal `session_id` values from
 * `exec_command` whose completion should wake the Agent in addition to fixed
 * wake sources (user messages, answered `ask_user` forms, and fired schedules).
 */
@Serializable
public data class AwaitEventArguments(
    public val terminals: List<Int> = emptyList(),
)

/**
 * Output returned by `await_event` when the Agent is woken.
 *
 * All wake payloads (direct user message, answered `ask_user` interaction,
 * fired schedule, or watched terminal exit) are delivered as dedicated
 * steerable commits via `steer` and consumed immediately after `await_event`
 * completes, so the tool output itself only records the wake [timestamp].
 */
@Serializable
public data class AwaitEventOutput(
    public val timestamp: Instant,
)
