package io.github.stream29.kodex.agentruntime.impl

/** Retry count chosen by this compaction runtime implementation. */
internal object CompactionRetryPolicy {
    const val maxRetries: Int = 20
}
