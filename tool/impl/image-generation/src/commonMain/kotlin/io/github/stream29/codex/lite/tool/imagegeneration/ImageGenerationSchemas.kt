package io.github.stream29.codex.lite.tool.imagegeneration

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder

public val ImageGenParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("prompt") {
            required = true
            string()
        }
        property("referenced_image_paths") {
            array {
                ofString()
            }
        }
        property("num_last_images_to_include") {
            integer()
        }
    }
