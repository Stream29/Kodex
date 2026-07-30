package io.github.stream29.kodex.cli.sessiontitle

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder

internal val SessionTitleOutputSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("title") {
            required = true
            string {
                minLength = SessionTitleMinimumLength
                maxLength = SessionTitleMaximumLength
            }
        }
    }
