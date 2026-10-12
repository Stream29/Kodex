package io.github.stream29.kodex.openai.codexclistorage

import kotlinx.serialization.SerializationException

/**
 * Read-only view of the external Codex CLI authentication and MCP declarations.
 *
 * This boundary never modifies Codex-owned files. Missing files are reported
 * as absent data rather than creating them, and MCP import candidates expose
 * typed declarations without leaking unsupported field values.
 */
public interface CodexCliStorage {
    /**
     * Reads `auth.json`, or returns `null` when the file is absent.
     *
     * Filesystem and malformed-file failures propagate; this operation does
     * not recover credentials from another source.
     *
     * @throws SerializationException if the present file cannot be decoded as
     * a Codex authentication record.
     */
    public suspend fun readAuthOrNull(): CodexAuthJson?

    /**
     * Reads and classifies Codex `mcp_servers` declarations for explicit import.
     *
     * Returns an empty list when `config.toml` or its `mcp_servers` table is
     * absent. Candidates are ordered by server name. A declaration is
     * supported only if its fields can be preserved by Kodex; unsupported
     * previews expose field names or a generic failure, never field values.
     * Filesystem and invalid-TOML failures propagate.
     */
    public suspend fun readMcpImportCandidates(): List<CodexCliMcpImportCandidate>
}
