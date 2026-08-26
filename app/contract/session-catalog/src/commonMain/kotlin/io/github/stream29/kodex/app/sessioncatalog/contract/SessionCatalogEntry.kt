package io.github.stream29.kodex.app.sessioncatalog.contract

import kotlin.time.Instant

/** Lightweight persisted Session summary that does not open its runtime. */
public data class SessionCatalogEntry(
    public val sessionIndex: Int,
    public val threadName: String? = null,
    public val lastActivityAt: Instant? = null,
    public val archived: Boolean = false,
) {
    init {
        require(sessionIndex >= 0) {
            "A Session catalog index must not be negative."
        }
        require(threadName == null || threadName.isNotBlank()) {
            "A Session catalog thread name must be null or non-blank."
        }
    }
}
