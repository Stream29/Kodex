package io.github.stream29.kodex.agentstorage.sqlite

import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlin.time.Instant

/** Fixed nanosecond precision lets the cwd/activity index distinguish Instants within one second. */
internal fun catalogActivityTimestamp(raw: String): String {
    val text = Instant.parse(raw).toString().removeSuffix("Z")
    val point = text.lastIndexOf('.')
    val seconds = if (point == -1) text else text.substring(0, point)
    val nano = if (point == -1) "" else text.substring(point + 1)
    return "$seconds.${nano.padEnd(9, '0')}Z"
}

/** Recompute can copy an older history timestamp, including a parseable non-UTC offset. */
internal fun SqliteConnection.normalizeCatalogActivity(rootAgentId: Int) {
    val key = rootAgentId.toLong()
    val raw = query("SELECT updated_at FROM session_catalog_view WHERE root_agent_id = ?", listOf(key))
        .singleOrNull()?.single() as String? ?: return
    val utc = catalogActivityTimestamp(raw)
    if (raw != utc) {
        execute("UPDATE session_catalog_view SET updated_at = ? WHERE root_agent_id = ?", listOf(utc, key))
    }
}
