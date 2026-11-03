package io.github.stream29.kodex.backend

import kotlinx.serialization.json.Json

public actual val LocalFrontendHostJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    classDiscriminator = "type"
}
