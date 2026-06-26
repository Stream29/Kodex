package io.github.stream29.codex.lite.tool.imagegeneration

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder

public val ImageGenParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("prompt") {
            required = true
            string { description = "Prompt describing the image to generate or the edit to apply." }
        }
        property("referenced_image_paths") {
            array {
                description = "Local image paths to use as edit inputs. Provide at most $ImageGenMaxEditImages paths."
                ofString()
            }
        }
        property("num_last_images_to_include") {
            integer {
                description = "Number of recent conversation images to edit. Must be between 1 and $ImageGenMaxEditImages."
            }
        }
    }
