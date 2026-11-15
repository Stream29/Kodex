package io.github.stream29.kodex.tool.readmedia

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder

public data class ReadMediaToolOptions(
    public val canRequestOriginalImageDetail: Boolean = true,
)

public fun readMediaParametersSchema(
    options: ReadMediaToolOptions = ReadMediaToolOptions(),
): ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("path") {
            required = true
            string {
                description = "Local filesystem path to an image file (absolute or relative to the current working directory)."
            }
        }
        if (options.canRequestOriginalImageDetail) {
            property("detail") {
                string {
                    description = "Image detail level. Defaults to `high`; use `original` to preserve exact resolution."
                    enum = listOf("high", "original")
                }
            }
        }
    }

public val ReadMediaOutputSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("image_url") {
            required = true
            string { description = "Data URL for the loaded image." }
        }
        property("detail") {
            required = true
            string {
                description = "Image detail hint returned by read_media (`high` or `original`)."
                enum = listOf("high", "original")
            }
        }
    }
