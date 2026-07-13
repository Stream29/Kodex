package io.github.stream29.codex.lite.openai

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Opaque identifier carried by a Responses API item.
 *
 * Server-provided values are intentionally not validated so historical and
 * provider-specific identifiers remain readable.
 */
@JvmInline
@Serializable
public value class ResponseItemId(public val value: String) {
    override fun toString(): String = value
}
