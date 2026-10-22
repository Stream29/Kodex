package io.github.stream29.kodex.mcp.contract

/**
 * Pure editor validation shared by local and RPC frontends; this performs no manager command.
 *
 * @throws IllegalArgumentException when the trimmed server name is blank.
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
