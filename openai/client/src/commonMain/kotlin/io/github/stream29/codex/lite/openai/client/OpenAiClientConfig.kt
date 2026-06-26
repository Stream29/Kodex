package io.github.stream29.codex.lite.openai.client

public data class OpenAiClientConfig(
    public val baseUrl: String = "https://chatgpt.com/backend-api/codex",
    public val clientVersion: String = "0.1.0",
    public val originator: String = DefaultCodexOriginator,
    public val userAgent: String = codexUserAgent(originator, clientVersion),
    public val defaultHeaders: Map<String, String> = emptyMap(),
    public val requestTimeoutMillis: Long = 300_000,
)

private const val DefaultCodexOriginator: String = "codex_cli_rs"

private fun codexUserAgent(
    originator: String,
    clientVersion: String,
): String =
    sanitizeHeaderValue("$originator/$clientVersion (CodexLite)")

private fun sanitizeHeaderValue(candidate: String): String {
    val sanitized = candidate.map { char ->
        if (char in ' '..'~') char else '_'
    }.joinToString(separator = "")
    return sanitized.ifBlank { DefaultCodexOriginator }
}
