package io.github.stream29.kodex.tool.awaitevent

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.ObjectPropertyBuilder
import kotlinx.schema.json.PropertyBuilder
import kotlinx.schema.json.obj

public val AwaitEventParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("name") {
            required = true
            string {
                description = "Short, specific, single-line waiting intent in the conversation's language. " +
                    "Do not copy arguments, include sensitive literals, or predict the result."
            }
        }
        property("events") {
            required = true
            array {
                minItems = 1
                description = "Any listed event can finish the wait."
                items {
                    oneOf {
                        obj { eventType("user_message") }
                        obj { eventType("ask_user_answered") }
                        obj { eventType("schedule_triggered") }
                        obj {
                            eventType("terminal_exited")
                            property("session_id") {
                                required = true
                                integer {
                                    description = "Background terminal session_id returned by exec_command."
                                    minimum = 1.0
                                    maximum = Int.MAX_VALUE.toDouble()
                                }
                            }
                        }
                        obj {
                            eventType("timeout")
                            property("duration_ms") {
                                required = true
                                integer {
                                    description = "Positive milliseconds from the start of this wait."
                                    minimum = 1.0
                                }
                            }
                        }
                    }
                }
            }
        }
    }

private fun ObjectPropertyBuilder.eventType(name: String) {
    additionalProperties = false
    property("type") {
        required = true
        string { enum = listOf(name) }
    }
}
