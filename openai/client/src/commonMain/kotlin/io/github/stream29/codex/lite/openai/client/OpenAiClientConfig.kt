package io.github.stream29.codex.lite.openai.client

public data class OpenAiClientConfig(
    public val baseUrl: String = "https://chatgpt.com/backend-api/codex",
    public val clientVersion: String = "0.1.0",
    public val defaultHeaders: Map<String, String> = emptyMap(),
    public val requestTimeoutMillis: Long = 300_000,
)

internal fun OpenAiClientConfig.url(path: String): String =
    "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
