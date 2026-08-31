package io.github.stream29.kodex.app.agent.contract

import kotlinx.coroutines.flow.StateFlow

/** Oldest-first snapshot of one Agent's sparse index timeline. */
public data class HistoryIndexWindow(
    public val generation: Long,
    public val indexes: List<Int>,
)

/** Display classification for one exact index entry. */
public enum class HistoryIndexEntryKind {
    CompactionPoint,
    UserMessage,
    AssistantMessage,
    AssistantCommentary,
    AssistantFinal,
    DeveloperMessage,
    AgentMessage,
    RequestUserInput,
    PlanUpdate,
}

/** Lazily loaded display data for one exact index entry. */
public data class HistoryIndexEntry(
    public val index: Int,
    public val kind: HistoryIndexEntryKind,
    public val summary: String,
)

/** Full renderer-neutral hover content for one exact index entry. */
public data class HistoryIndexEntryDetail(
    public val kind: HistoryIndexEntryKind,
    public val content: String,
)

/** Sparse index timeline owned by one materialized Agent ViewModel. */
public interface HistoryIndexViewModel {
    /** Stored index entries in oldest-first order. */
    public val window: StateFlow<HistoryIndexWindow>

    /** Returns whether [index] still belongs to [generation]. */
    public fun contains(generation: Long, index: Int): Boolean

    /** Loads the exact entry at [index] for one composed row. */
    public suspend fun load(generation: Long, index: Int): HistoryIndexEntry

    /** Loads full hover content for the exact entry at [index]. */
    public suspend fun loadDetail(generation: Long, index: Int): HistoryIndexEntryDetail
}
