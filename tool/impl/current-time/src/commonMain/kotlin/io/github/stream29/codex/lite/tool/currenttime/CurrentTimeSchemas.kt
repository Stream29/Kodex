package io.github.stream29.codex.lite.tool.currenttime

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder

public val CurrentTimeParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
    }

public val CurrentTimeOutputSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("current_time") {
            required = true
            string { description = "Current UTC time formatted as YYYY-MM-DD HH:MM:SS UTC." }
        }
    }
