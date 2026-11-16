package io.github.stream29.kodex.tool.imagegeneration

import kotlinx.io.files.Path

public actual fun imageArtifactDirectory(kodexHome: Path, sessionId: String): Path =
    Path(kodexHome, "media", "generated", sessionId.toImageArtifactPathSegment())

internal fun String.toImageArtifactPathSegment(): String =
    map { character ->
        if (character.isLetterOrDigit() && character.code < 128 || character == '-' || character == '_') {
            character
        } else {
            '_'
        }
    }
        .joinToString(separator = "")
        .ifEmpty { "generated_image" }
