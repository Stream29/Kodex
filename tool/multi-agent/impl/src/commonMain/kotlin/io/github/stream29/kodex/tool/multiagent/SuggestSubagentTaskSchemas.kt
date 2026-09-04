package io.github.stream29.kodex.tool.multiagent

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder

public val SuggestSubagentTaskParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("tasks") {
            required = true
            array {
                description =
                    "A batch of independent tasks. Each task creates one ordinary new Session after the user accepts the batch. Do not include model, cwd, reasoning, service tier, or user-interaction settings; the user selects those together in the confirmation UI."
                ofObject {
                    additionalProperties = false
                    property("name") {
                        required = true
                        string {
                            description = "The name of the new Session."
                        }
                    }
                    property("prompt") {
                        required = true
                        string {
                            description =
                                "A self-contained initial user message for the new Session; it has no source Session history."
                        }
                    }
                }
            }
        }
    }
