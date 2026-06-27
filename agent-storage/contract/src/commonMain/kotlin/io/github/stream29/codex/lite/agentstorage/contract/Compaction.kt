package io.github.stream29.codex.lite.agentstorage.contract

import io.github.stream29.codex.lite.openai.ResponseItem

/**
 * Checkpoint that defines the compacted model-visible prefix active at an
 * agent-storage index.
 *
 * A checkpoint does not rewrite raw [ResponseItem] history. It says that, for
 * a snapshot index where this checkpoint is visible, model input starts with
 * [prefix] and then continues with raw history items from [historyBaseIndex]
 * through the snapshot index.
 *
 * Writers publish a checkpoint before publishing the history item that makes
 * the checkpoint visible. Readers use [CodexAgentRawDataStorage.latestIndex] as
 * the visibility boundary before reading this timeline.
 *
 * @property prefix Replacement model-visible base history. This should contain
 * the compaction summary and any retained or re-injected context needed for the
 * next model request.
 * @property historyBaseIndex First raw history index not covered by [prefix].
 * Raw history items before this index remain stored for audit/forking, but are
 * excluded from the active prompt projection while this checkpoint is visible.
 */
public data class CompactionCheckpoint(
    public val prefix: List<ResponseItem>,
    public val historyBaseIndex: Int
)
