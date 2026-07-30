package io.github.stream29.kodex.tool.viewimage

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
                    description = "Image detail level. Defaults to `high`; use `original` to preserve exact resolution."
                    enum = listOf("high", "original")
                }
            }
        }
        if (options.includeEnvironmentId) {
            property("environment_id") {
                string {
                    description = "Environment id from <environment_context>. Omit to use the primary environment."
                }
            }
        }
    }

public val ViewImageOutputSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("image_url") {
            required = true
            string { description = "Data URL for the loaded image." }
        }
        property("detail") {
            required = true
            string {
                description = "Image detail hint returned by view_image. Returns `high` for default resized behavior or `original` when original resolution is preserved."
                enum = listOf("high", "original")
            }
        }
    }
