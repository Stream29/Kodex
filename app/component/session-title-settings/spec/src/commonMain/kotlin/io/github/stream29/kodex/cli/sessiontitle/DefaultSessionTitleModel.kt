package io.github.stream29.kodex.cli.sessiontitle

import io.github.stream29.kodex.openai.OpenAiModelId

/** Compiled model used when global settings do not override title generation. */
public val DefaultSessionTitleModel: OpenAiModelId = OpenAiModelId("gpt-5.3-codex-spark")
