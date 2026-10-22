package io.github.stream29.kodex.mcp.contract

public actual fun String.toModelToolName(): String =
    map { character ->
        when (character) {
            in 'a'..'z',
            in 'A'..'Z',
            in '0'..'9',
            '_' -> character

            else -> '_'
        }
    }.joinToString(separator = "").ifEmpty { "_" }

public actual fun requireUniqueMcpModelNames(names: Collection<String>, kind: String): Unit {
    val collisions = names.groupBy { it.toModelToolName() }.filterValues { it.size > 1 }
    require(collisions.isEmpty()) {
        "Ambiguous MCP $kind model routes: " +
            collisions.entries.joinToString { (route, raw) -> "'$route' from ${raw.joinToString { "'$it'" }}" }
    }
}
