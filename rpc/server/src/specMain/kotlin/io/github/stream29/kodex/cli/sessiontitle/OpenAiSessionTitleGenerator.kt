@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.sessiontitle

import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.client.contract.OpenAiClient

/** Responses API implementation of the Codex App-style session title request. */
public expect class OpenAiSessionTitleGenerator(client: OpenAiClient) : SessionTitleGenerator {
    override suspend fun generateTitle(
        userText: String,
        model: OpenAiModelId,
        reasoningEffort: ReasoningEffort,
    ): SessionTitleGenerationResult
}
