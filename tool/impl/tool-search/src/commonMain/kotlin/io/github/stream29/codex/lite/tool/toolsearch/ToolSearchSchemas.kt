package io.github.stream29.codex.lite.tool.toolsearch

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder

public val ToolSearchParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("query") {
            required = true
            string { description = "Search query for deferred tools." }
        }
        property("limit") {
            integer { description = "Maximum number of tools to return. Defaults to $ToolSearchDefaultLimit." }
        }
    }
