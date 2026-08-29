package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.agentstorage.cleanmodels.CleanOpenAiEvent

/**
 * Completed clean event persisted in either the index or work timeline.
 *
 * Persistence serializes the timeline-specific sealed unions rather than this
 * shared projection contract.
 */
public interface StableCleanEvent : CleanOpenAiEvent {
    /**
     * Completed clean event produced by a tool handler.
     *
     * This cross-timeline contract prevents tool execution from publishing
     * messages, reasoning, or other non-tool stable events.
     */
    public sealed interface CompletedTool : StableCleanEvent {
        /** Completed tool assigned to the index timeline. */
        public interface Index : CompletedTool

        /** Completed tool assigned to the work timeline. */
        public interface Work : CompletedTool
    }
}
