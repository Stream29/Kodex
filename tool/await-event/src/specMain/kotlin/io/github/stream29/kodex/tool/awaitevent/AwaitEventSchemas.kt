package io.github.stream29.kodex.tool.awaitevent

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder

public val AwaitEventParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("terminals") {
            array {
                description =
                    "Optional list of background terminal session_id values to monitor for process exit."
                ofInteger {
                    description = "Background terminal session_id returned by exec_command."
                }
            }
        }
    }
