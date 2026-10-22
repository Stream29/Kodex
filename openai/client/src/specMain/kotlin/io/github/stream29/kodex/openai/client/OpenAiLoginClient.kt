package io.github.stream29.kodex.openai.client

import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient as OpenAiLoginClientContract

/** Creates an OpenAI OAuth client using the configured Codex-compatible defaults. */
public expect fun OpenAiLoginClient(): OpenAiLoginClientContract
