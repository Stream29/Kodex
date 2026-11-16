package io.github.stream29.kodex.tool.readmedia

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder

public fun readMediaParametersSchema(): ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("path") {
            required = true
            string {
                description = "Local filesystem path to an image file (absolute or relative to the current working directory)."
            }
        }
        property("detail") {
            string {
                description = "Image detail level. Defaults to `high`; use `original` to preserve exact resolution."
                enum = listOf("high", "original")
            }
        }
    }
