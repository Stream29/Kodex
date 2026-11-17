package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.openai.ResponseItem

/**
 * Projects a conversation commit to ordered OpenAI history items.
 *
 * @throws IllegalArgumentException if the commit is settings, request metadata, or a runtime state snapshot.
 */
public expect fun AgentCommit.toResponseHistoryItems(): List<ResponseItem.HistoryItem>
