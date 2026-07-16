package io.github.stream29.codex.lite.tool.plan

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder

public val UpdatePlanParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("explanation") {
            string { description = "Optional explanation for this plan update." }
        }
        property("plan") {
            required = true
            array {
                description = "The list of steps"
                ofObject {
                    additionalProperties = false
                    property("step") {
                        required = true
                        string { description = "Task step text." }
                    }
                    property("status") {
                        required = true
                        string {
                            description = "Step status."
                            enum = listOf("pending", "in_progress", "completed")
                        }
                    }
                }
            }
        }
    }
