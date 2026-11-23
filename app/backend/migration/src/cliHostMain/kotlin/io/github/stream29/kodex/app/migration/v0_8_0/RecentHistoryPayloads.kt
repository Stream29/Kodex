package io.github.stream29.kodex.app.migration.v0_8_0

import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Normalizes stored payloads from both recent legacy checkpoints. The caller owns
 * the write transaction and must roll it back on failure.
 */
internal fun normalizeRecentHomeHistory(connection: SqliteConnection) {
    var after: List<Any?> = listOf(-1L, "", -1L)
    while (true) {
        val page = connection.query(
            """
            SELECT agent_id, type, "index", payload_json FROM agent_commits
            WHERE (agent_id, type, "index") > (?, ?, ?)
              AND type IN ('settings', 'command_execution_tool_event', 'await_event_tool_event',
                           'event_terminal_exited', 'unstable', 'steer')
            ORDER BY agent_id, type, "index" LIMIT 32
            """.trimIndent(),
            after,
        )
        if (page.isEmpty()) return
        for ((agent, rawType, index, payload) in page) {
            val type = rawType as String
            val original = payload as String
            val location = "agent_id=$agent, type=$type, index=$index"
            val replacement = try {
                normalizePayload(type, original)
            } catch (failure: InvalidHistoryPayload) {
                // JSON parser errors can contain parts of the original payload.
                error("Invalid recent history payload ($location): ${failure.message}")
            } ?: continue

            // Re-encoding a JSON object with duplicate keys would silently drop values.
            check(connection.query(
                """
                SELECT 1 FROM json_tree(?) WHERE typeof(key) = 'text'
                GROUP BY parent, key HAVING count(*) > 1 LIMIT 1
                """.trimIndent(),
                listOf(original),
            ).isEmpty()) { "Duplicate JSON member in recent history payload ($location)." }
            connection.execute(
                """UPDATE agent_commits SET payload_json = ?
                   WHERE agent_id = ? AND type = ? AND "index" = ? AND payload_json = ?""",
                listOf(replacement, agent, type, index, original),
            )
            check(connection.changes() == 1) { "Recent history row changed during normalization ($location)." }
        }
        after = page.last().take(3)
    }
}

private fun normalizePayload(type: String, original: String): String? {
    val payload = try {
        Json.parseToJsonElement(original)
    } catch (_: IllegalArgumentException) {
        invalid("expected valid JSON")
    }
    val updated = when (type) {
        "settings" -> normalizeSettings(payload.objectAt("settings"))
        "command_execution_tool_event" -> normalizeCommand(payload.tagged(type))
        "await_event_tool_event" -> normalizeAwait(payload.tagged(type))
        "event_terminal_exited" -> normalizeTerminalExit(payload.tagged(type))
        "unstable", "steer" -> JsonArray(payload.arrayAt(type).map { element ->
            val entry = element.objectAt("$type entry")
            when (entry.requiredTag("$type entry")) {
                "command_execution" -> if (type == "unstable") normalizeCommand(entry) else entry
                "await_event" -> if (type == "unstable") normalizeAwait(entry) else entry
                "event_terminal_exited" -> if (type == "steer") normalizeTerminalExit(entry) else entry
                else -> entry
            }
        })
        else -> error("Unexpected recent history row type: $type")
    }
    return updated.takeUnless { it == payload }?.toString()
}

private fun normalizeSettings(settings: JsonObject): JsonObject {
    val old = settings["threadName"]?.strictString() ?: run {
        if ("threadName" in settings) invalid("settings.threadName must be a string")
        if ("name" in settings && settings["name"]?.strictString() == null) invalid("settings.name must be a string")
        return settings
    }
    if ("name" in settings) {
        val new = settings["name"]?.strictString() ?: invalid("settings.name must be a string")
        if (old != new) invalid("conflicting settings.threadName and settings.name")
    }
    return JsonObject(settings - "threadName" + ("name" to JsonPrimitive(old)))
}

private fun normalizeCommand(command: JsonObject): JsonObject {
    val action = command["action"].objectAt("action")
    return when (action.requiredTag("action")) {
        "exec_command" -> {
            val arguments = action["arguments"].objectAt("action.arguments").withIntentName("Run a command")
            command.withField("action", action.withField("arguments", arguments))
        }
        "write_stdin" -> command
        else -> invalid("unrecognized command action type")
    }
}

private fun normalizeTerminalExit(event: JsonObject): JsonObject {
    val terminalId = event["session_id"].intAt("session_id")
    return event.withIntentName("Terminal #$terminalId")
}

private fun normalizeAwait(await: JsonObject): JsonObject {
    val arguments = await["arguments"].objectAt("arguments")
    val events = if ("events" in arguments) {
        if ("terminals" in arguments) invalid("await arguments contain both events and terminals")
        arguments["events"].arrayAt("arguments.events").also { events ->
            if (events.isEmpty()) invalid("arguments.events must not be empty")
            events.forEach { element ->
                val event = element.objectAt("arguments.events entry")
                when (event.requiredTag("arguments.events entry")) {
                    "user_message", "ask_user_answered", "schedule_triggered" -> Unit
                    "terminal_exited" -> event["session_id"].intAt("arguments.events.session_id")
                    "timeout" -> {
                        val millis = event["duration_ms"].integerAt("arguments.events.duration_ms")
                        val duration = millis.toDuration(DurationUnit.MILLISECONDS)
                        if (millis <= 0L || !duration.isFinite() || duration.inWholeMilliseconds != millis) {
                            invalid("arguments.events.duration_ms must be a representable positive duration")
                        }
                    }
                    else -> invalid("unrecognized await event type")
                }
            }
        }
    } else {
        val terminals = arguments["terminals"]?.arrayAt("arguments.terminals").orEmpty()
        if (terminals.isEmpty()) {
            JsonArray(listOf("user_message", "ask_user_answered", "schedule_triggered").map {
                JsonObject(mapOf("type" to JsonPrimitive(it)))
            })
        } else {
            JsonArray(terminals.map {
                JsonObject(mapOf(
                    "type" to JsonPrimitive("terminal_exited"),
                    "session_id" to JsonPrimitive(it.intAt("arguments.terminals entry")),
                ))
            })
        }
    }
    val updated = JsonObject(arguments - "terminals" + ("events" to events)).withIntentName("Wait for events")
    return await.withField("arguments", updated)
}

private fun JsonObject.withIntentName(fallback: String): JsonObject {
    val name = this["name"]?.strictString()
    if (name != null && name.isNotBlank() && name.none(Char::isIntentSeparator)) return this
    val normalized = name?.map { if (it.isIntentSeparator()) ' ' else it }?.joinToString("")?.trim()
    return withField("name", JsonPrimitive(normalized?.takeIf(String::isNotEmpty) ?: fallback))
}

// Freeze the generation-1 name rules here rather than depending on a future tool decoder.
private fun Char.isIntentSeparator(): Boolean =
    this in '\u0000'..'\u001f' || this in '\u007f'..'\u009f' || this == '\u2028' || this == '\u2029'

private fun JsonObject.withField(name: String, value: JsonElement): JsonObject =
    if (this[name] == value) this else JsonObject(this + (name to value))

private fun JsonElement.tagged(type: String): JsonObject = objectAt(type).also {
    if (it.requiredTag(type) != type) invalid("payload discriminator does not match its row type")
}

private fun JsonObject.requiredTag(field: String): String =
    this["type"]?.strictString()?.takeIf(String::isNotEmpty) ?: invalid("$field.type must be a nonempty string")

private fun JsonElement?.objectAt(field: String): JsonObject =
    this as? JsonObject ?: invalid("$field must be an object")

private fun JsonElement?.arrayAt(field: String): JsonArray =
    this as? JsonArray ?: invalid("$field must be an array")

private fun JsonElement.strictString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.integerAt(field: String): Long =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()
        ?: invalid("$field must be a JSON integer")

private fun JsonElement?.intAt(field: String): Int =
    integerAt(field).takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
        ?: invalid("$field must be within the Int range")

private class InvalidHistoryPayload(message: String) : IllegalArgumentException(message)

private fun invalid(message: String): Nothing = throw InvalidHistoryPayload(message)
