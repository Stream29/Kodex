package io.github.stream29.codex.lite.openai.client

public data class OpenAiClientConfig(
    public val baseUrl: String = "https://chatgpt.com/backend-api/codex",
    public val clientVersion: String = "0.1.0",
    public val originator: String = DefaultCodexOriginator,
    public val userAgent: String = codexUserAgent(originator, clientVersion),
    public val defaultHeaders: Map<String, String> = emptyMap(),
    public val requestTimeoutMillis: Long = 300_000,
    public val retry: OpenAiClientRetryConfig = OpenAiClientRetryConfig(),
)

/**
 * Retry policy for transient OpenAI HTTP failures.
 *
 * Non-transient client errors such as 403 and 404 are deliberately not retryable.
 */
public data class OpenAiClientRetryConfig(
    public val maxRetries: Int = 4,
    public val baseDelayMillis: Long = 200,
    public val maxDelayMillis: Long = 10_000,
    public val randomizationMillis: Long = 100,
    public val retryRateLimited: Boolean = true,
    public val retryServerErrors: Boolean = true,
    public val retryTransport: Boolean = true,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be non-negative." }
        require(baseDelayMillis > 0) { "baseDelayMillis must be positive." }
        require(maxDelayMillis > 0) { "maxDelayMillis must be positive." }
        require(randomizationMillis >= 0) { "randomizationMillis must be non-negative." }
    }
}

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
