package io.github.stream29.kodex.openai.jsoncodec

import kotlinx.serialization.json.Json

public val OpenAiJsonCodec: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
