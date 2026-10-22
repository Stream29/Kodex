package io.github.stream29.kodex.openai

import kotlinx.serialization.Serializable

/**
 * Opaque identifier carried by a Responses API item.
 *
 * Server-provided values are intentionally not validated so historical and
 * provider-specific identifiers remain readable.
 */
@Serializable
public value class ResponseItemId(public val value: String) {
    override fun toString(): String = value
}
