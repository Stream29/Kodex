package io.github.stream29.kodex.mcp.contract

/**
 * Built-in top-level `kodex` CLI domain names reserved by the host.
 *
 * New or renamed MCP servers cannot use these names ([validatedName]); legacy persisted
 * configurations sharing a reserved name remain loadable so users can inspect, rename, or
 * delete them, while the CLI skips mounting them as top-level domains and flags the conflict
 * in `kodex mcp list`.
 */
public val ReservedMcpServerNames: Set<String> = setOf(
    "tui",
    "web",
    "app",
    "host",
    "backend",
    "terminal",
    "ask",
    "schedule",
    "session",
    "agent",
    "search",
    "image",
    "mcp",
    "tool",
)

/**
 * Pure editor validation shared by local and RPC frontends; this performs no manager command.
 *
 * @throws IllegalArgumentException when the trimmed server name is blank or collides with
 * [ReservedMcpServerNames].
 */
public expect fun McpServerDraft.validatedName(): String

/**
 * Keeps existing secrets only when explicitly requested, without synthesizing credentials.
 *
 * @throws IllegalArgumentException when the endpoint/command or a secret-entry name is blank,
 * normalized secret-entry names collide, a requested retained secret has no existing value,
 * or OAuth specifies a blank redirect URI or a client secret without a client id.
 */
public expect fun McpServerDraft.toConfiguration(
    existing: McpServerConfiguration?,
    preserveOAuth: Boolean,
): McpServerConfiguration
