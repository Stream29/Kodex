package io.github.stream29.kodex.mcp.contract

/** The original MCP raw-name → model-route component projection; never a persisted name. */
public expect fun String.toModelToolName(): String

/**
 * Rejects ambiguous executable names without changing raw configuration/catalog
 * values. Server components must be unique globally (including disabled servers);
 * tool components must be unique within their server's complete paginated catalog.
 * Throws [IllegalArgumentException] naming the ambiguous route components.
 */
public expect fun requireUniqueMcpModelNames(names: Collection<String>, kind: String): Unit
