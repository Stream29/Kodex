package io.github.stream29.kodex.cli.history

/**
 * Canonical stable tool summary mapping shared across collapsed headers and expanded tool views,
 * preserving legacy migrated tool names for historical sessions.
 */
public expect fun functionToolSummary(
    name: String,
    namespace: String?,
    failed: Boolean = false,
): String

public expect fun customToolSummary(
    name: String,
    namespace: String?,
    input: String,
    failed: Boolean = false,
): String
