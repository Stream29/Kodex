package io.github.stream29.kodex.agentstorage.sqlite

import io.github.stream29.kodex.utils.sqlite.SqliteConnection

/** A catalog row exists in this group; a null wrapper means the root has no catalog row. */
public data class SessionCatalogGroup(public val cwd: String?)

/** Captures one root's directory within the transaction that will change the catalog or archive flag. */
public expect fun SqliteConnection.sessionCatalogGroup(rootAgentId: Int): SessionCatalogGroup?

/**
 * Records a root mutation once, after its primary writes but before commit.
 *
 * The returned keys are the groups to invalidate only after successful commit. The boolean says
 * whether that group still exists. Equal before/after groups still count as one mutation.
 */
public expect fun SqliteConnection.recordSessionGroupMutation(
    before: SessionCatalogGroup?,
    after: SessionCatalogGroup?,
): Map<String?, Boolean>
