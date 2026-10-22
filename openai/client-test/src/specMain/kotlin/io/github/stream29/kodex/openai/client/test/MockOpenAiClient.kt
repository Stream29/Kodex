package io.github.stream29.kodex.openai.client.test

import io.github.stream29.kodex.openai.client.contract.OpenAiClient

public expect fun mockOpenAiClient(
    block: MockOpenAiClientBuilder.() -> Unit = {},
): OpenAiClient

/** Creates a test-only OpenAI client handler builder. */
public expect fun MockOpenAiClientBuilder(): MockOpenAiClientBuilder
