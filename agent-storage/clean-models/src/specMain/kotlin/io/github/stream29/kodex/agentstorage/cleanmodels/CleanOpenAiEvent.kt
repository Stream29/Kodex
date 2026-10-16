package io.github.stream29.kodex.agentstorage.cleanmodels

import io.github.stream29.kodex.openai.ResponseItem

/**
 * Durable clean event that can restore its model-visible OpenAI history.
 *
 * Stable and unstable events keep independent serialization hierarchies. This
 * interface only defines their common projection contract.
 */
public interface CleanOpenAiEvent {
    /**
     * Restores the ordered OpenAI history items represented by this event.
     */
    public fun toResponseHistoryItems(): List<ResponseItem.HistoryItem>
}
