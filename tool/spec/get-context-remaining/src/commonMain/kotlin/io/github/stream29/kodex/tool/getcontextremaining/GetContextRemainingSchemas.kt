package io.github.stream29.kodex.tool.getcontextremaining

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder
import kotlinx.schema.json.GenericPropertyDefinition
import kotlinx.schema.json.JsonSchemaConstants.Types.NULL_TYPE
import kotlinx.schema.json.integer

public val GetContextRemainingParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
    }

public val GetContextRemainingOutputSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("tokens_left") {
            required = true
            anyOf {
                description = "Remaining tokens in the current context window, or null when unavailable."
                integer()
                addOption(GenericPropertyDefinition(type = NULL_TYPE))
            }
        }
    }
