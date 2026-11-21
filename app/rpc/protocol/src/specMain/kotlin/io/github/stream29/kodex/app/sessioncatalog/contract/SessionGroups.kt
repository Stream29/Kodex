package io.github.stream29.kodex.app.sessioncatalog.contract

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
public data class SessionGroupSummary(
    public val cwd: String?,
    public val groupNonce: Long,
    public val latestUpdatedAt: Instant?,
    public val hasRunning: Boolean,
)

@Serializable
public data class SessionGroupPage(
    public val cwd: String?,
    public val groupNonce: Long,
    public val entries: List<SessionCatalogEntry>,
    /** Number of matching entries omitted by the requested limit. */
    public val hiddenCount: Int,
)
