package io.github.stream29.codex.lite.tool.viewimage

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder

public data class ViewImageToolOptions(
    public val canRequestOriginalImageDetail: Boolean = false,
    public val includeEnvironmentId: Boolean = false,
)

public fun viewImageParametersSchema(
    options: ViewImageToolOptions = ViewImageToolOptions(),
): ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("path") {
            required = true
            string { description = "Local filesystem path to an image file." }
        }
        if (options.canRequestOriginalImageDetail) {
            property("detail") {
                string {
                    description = "Image detail level. Defaults to high."
                    enum = listOf("high", "original")
                }
            }
        }
        if (options.includeEnvironmentId) {
            property("environment_id") {
                string { description = "Environment identifier containing the image file." }
            }
        }
    }

public val ViewImageOutputSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("image_url") {
            required = true
            string { description = "Base64 data URL for the prepared image." }
        }
        property("detail") {
            required = true
            string {
                description = "Detail level used for the returned image."
                enum = listOf("high", "original")
            }
        }
    }
