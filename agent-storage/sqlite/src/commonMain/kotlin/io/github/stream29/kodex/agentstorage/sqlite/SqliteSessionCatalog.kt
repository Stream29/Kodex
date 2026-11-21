package io.github.stream29.kodex.agentstorage.sqlite

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.utils.sqlite.SqliteConnection

internal fun SqliteConnection.appendSessionCatalog(
    agentId: Long, index: Int, timestamp: String, settings: KodexAgentSettings?,
) {
    val activity = catalogActivityTimestamp(timestamp)
    // A child has no catalog row; the same primary-key probe handles both cases.
    if (settings == null) {
        execute(
            """UPDATE session_catalog_view SET created_at = CASE WHEN ? = 0 THEN ? ELSE created_at END,
                updated_at = ? WHERE root_agent_id = ?""",
            listOf(index.toLong(), timestamp, activity, agentId),
        )
    } else {
        execute(
            """UPDATE session_catalog_view SET created_at = CASE WHEN ? = 0 THEN ? ELSE created_at END,
                updated_at = ?, name = ?, cwd = ? WHERE root_agent_id = ?""",
            listOf(index.toLong(), timestamp, activity, settings.name, settings.cwd.toString(), agentId),
        )
    }
}

public actual fun SqliteConnection.recomputeSessionCatalog(rootAgentId: Int) {
    execute(
        """UPDATE session_catalog_view AS target
           SET (name, cwd, created_at, updated_at) = (
               SELECT
                   CASE WHEN st.agent_id IS NULL THEN NULL
                       ELSE coalesce(json_extract(st.payload_json, '$.name'), '') END,
                   CASE WHEN st.agent_id IS NULL THEN NULL ELSE coalesce(json_extract(st.payload_json, '$.cwd'), '.') END,
                   (SELECT c.created_at FROM agent_commits AS c INDEXED BY commits_global_index
                       WHERE c.agent_id = target.root_agent_id AND c."index" = 0 ORDER BY c.type LIMIT 1),
                   (SELECT c.created_at FROM agent_commits AS c INDEXED BY commits_global_index
                       WHERE c.agent_id = target.root_agent_id ORDER BY c."index" DESC, c.type DESC LIMIT 1)
               FROM (SELECT 1)
               LEFT JOIN agent_commits AS st ON st.agent_id = target.root_agent_id AND st.type = 'settings'
                   AND st."index" = (SELECT s."index" FROM agent_commits AS s
                       WHERE s.agent_id = target.root_agent_id AND s.type = 'settings' ORDER BY s."index" DESC LIMIT 1)
           )
           WHERE target.root_agent_id = ?""",
        listOf(rootAgentId.toLong()),
    )
    if (changes() != 0) normalizeCatalogActivity(rootAgentId)
}
