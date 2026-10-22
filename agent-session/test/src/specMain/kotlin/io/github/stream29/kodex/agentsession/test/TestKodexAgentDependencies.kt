package io.github.stream29.kodex.agentsession.test

import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient

/** Dependencies for session tests that do not execute external services. */
public expect fun testKodexAgentDependencies(
    client: OpenAiClient = mockOpenAiClient(),
): KodexAgentDependencies
