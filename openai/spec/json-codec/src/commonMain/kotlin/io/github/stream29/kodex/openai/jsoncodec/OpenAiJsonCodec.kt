package io.github.stream29.kodex.openai.jsoncodec

import kotlinx.serialization.json.Json

/**
 * Shared OpenAI wire JSON policy.
 *
 * Decoding ignores unknown keys; encoding omits explicit nulls and includes
 * default values. Models and consumers use this same value so their wire
 * projections do not silently diverge.
 */
public val OpenAiJsonCodec: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
