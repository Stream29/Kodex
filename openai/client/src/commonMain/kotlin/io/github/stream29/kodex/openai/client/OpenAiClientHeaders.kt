package io.github.stream29.kodex.openai.client

internal actual fun codexUserAgent(
    originator: String,
    clientVersion: String,
): String =
    sanitizeHeaderValue("$originator/$clientVersion (Kodex)")

private fun sanitizeHeaderValue(candidate: String): String {
    val sanitized = candidate.map { char ->
        if (char in ' '..'~') char else '_'
    }.joinToString(separator = "")
    return sanitized.ifBlank { DefaultCodexOriginator }
}
