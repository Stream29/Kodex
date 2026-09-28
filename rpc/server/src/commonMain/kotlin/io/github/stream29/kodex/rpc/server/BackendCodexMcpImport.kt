package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.mcp.contract.McpCodexImportCandidate
import io.github.stream29.kodex.mcp.contract.McpOAuthClient
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpTransportKind
import io.github.stream29.kodex.openai.codexclistorage.CodexCliMcpAuth
import io.github.stream29.kodex.openai.codexclistorage.CodexCliMcpImportCandidate
import io.github.stream29.kodex.openai.codexclistorage.CodexCliMcpServer
import io.github.stream29.kodex.openai.codexclistorage.CodexCliMcpTransportKind
import io.github.stream29.kodex.openai.codexclistorage.CodexCliStorage
import kotlinx.io.files.Path

/** Explicit read only; no preview registry, credentials import or background polling. */
internal suspend fun readBackendCodexMcpSettings(home: Path): List<McpCodexImportCandidate> =
    CodexCliStorage(home).readMcpImportCandidates().map { candidate ->
        when (candidate) {
            is CodexCliMcpImportCandidate.Supported ->
                McpCodexImportCandidate.Supported(candidate.serverName, candidate.configuration.toConfiguration())
            is CodexCliMcpImportCandidate.Unsupported ->
                McpCodexImportCandidate.Unsupported(
                    candidate.serverName,
                    when (candidate.transport) {
                        CodexCliMcpTransportKind.Stdio -> McpTransportKind.Stdio
                        CodexCliMcpTransportKind.StreamableHttp -> McpTransportKind.StreamableHttp
                        null -> null
                    },
                    candidate.detail,
                )
        }
    }.sortedBy { it.serverName }

private fun CodexCliMcpServer.toConfiguration(): McpServerConfiguration = when (this) {
    is CodexCliMcpServer.Stdio -> McpServerConfiguration.Stdio(
        command = command.trim(),
        args = args,
        environment = env.mapKeys { it.key.trim() }.mapValues { McpSecret(it.value) },
        workingDirectory = Path(cwd),
        enabled = enabled,
    )
    is CodexCliMcpServer.StreamableHttp -> McpServerConfiguration.StreamableHttp(
        url = url.trim(),
        headers = headers.mapKeys { it.key.trim() }.mapValues { McpSecret(it.value) },
        oauth = if (oauth != null || scopes != null || oauthResource != null || auth == CodexCliMcpAuth.OAuth) {
            McpOAuthConfiguration.Uninitialized(
                client = McpOAuthClient(clientId = oauth?.clientId?.trim()?.takeIf(String::isNotEmpty)),
                resource = oauthResource?.trim()?.takeIf(String::isNotEmpty),
                scopes = scopes.orEmpty().map(String::trim).filter(String::isNotEmpty).distinct(),
            )
        } else null,
        enabled = enabled,
    )
}
