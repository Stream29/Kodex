package io.github.stream29.kodex.agentstorage.cleanmodels

import io.github.stream29.kodex.tool.awaitevent.AwaitEventArguments
import io.github.stream29.kodex.tool.awaitevent.AwaitEventInput
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.intOrNull

internal object StoredAwaitEventArgumentsSerializer :
    JsonTransformingSerializer<AwaitEventArguments>(AwaitEventArguments.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val fields = element as? JsonObject ?: return element
        if ("events" in fields) {
            if ("terminals" in fields) throw SerializationException("Mixed legacy and current await_event arguments.")
            return element
        }
        if (fields.keys.any { it != "terminals" }) {
            throw SerializationException("Unknown legacy await_event argument.")
        }
        val terminals = fields["terminals"]?.let {
            it as? JsonArray ?: throw SerializationException("Legacy await_event terminals must be an array.")
        }.orEmpty()
        val events = if (terminals.isEmpty()) {
            listOf(AwaitEventInput.UserMessage, AwaitEventInput.AskUserAnswered, AwaitEventInput.ScheduleTriggered)
        } else {
            terminals.map {
                val id = (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.intOrNull
                    ?: throw SerializationException("Legacy await_event terminal ID must be an integer.")
                AwaitEventInput.TerminalExited(id)
            }
        }
        return Json.encodeToJsonElement(AwaitEventArguments.serializer(), AwaitEventArguments(events))
    }
}
