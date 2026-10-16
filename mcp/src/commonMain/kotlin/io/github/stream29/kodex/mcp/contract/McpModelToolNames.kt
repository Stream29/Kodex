package io.github.stream29.kodex.mcp.contract

/** The original MCP raw-name → model-route component projection; never a persisted name. */
public fun String.toModelToolName(): String =
    map { character ->
        when (character) {
            in 'a'..'z',
            in 'A'..'Z',
            in '0'..'9',
            '_' -> character

            else -> '_'
        }
    }.joinToString(separator = "").ifEmpty { "_" }

/**
 * Rejects ambiguous executable names without changing raw configuration/catalog
 * values. Server components must be unique globally (including disabled servers);
 * tool components must be unique within their server's complete paginated catalog.
 * Throws [IllegalArgumentException] naming the ambiguous route components.
 */
public fun requireUniqueMcpModelNames(names: Collection<String>, kind: String) {
    val collisions = names.groupBy { it.toModelToolName() }.filterValues { it.size > 1 }
    require(collisions.isEmpty()) {
        "Ambiguous MCP $kind model routes: " +
            collisions.entries.joinToString { (route, raw) -> "'$route' from ${raw.joinToString { "'$it'" }}" }
    }
}
