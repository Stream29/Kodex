package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.cli.settings.KodexGlobalSettingsStore
import io.github.stream29.kodex.mcp.contract.McpCodexImportCandidate
import io.github.stream29.kodex.mcp.contract.McpConfigurationStore
import io.github.stream29.kodex.mcp.contract.McpOAuthClient
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.openai.codexclistorage.CodexCliMcpAuth
import io.github.stream29.kodex.openai.codexclistorage.CodexCliMcpServer
import io.github.stream29.kodex.openai.codexclistorage.CodexCliMcpImportCandidate
import io.github.stream29.kodex.openai.codexclistorage.CodexCliMcpTransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.io.files.Path

/** MCP-specific atomic adapter over Kodex's global settings authority. */
internal class KodexMcpConfigurationStore(
    private val settings: KodexGlobalSettingsStore,
    scope: CoroutineScope,
) : McpConfigurationStore {
    override val configurations: StateFlow<Map<String, McpServerConfiguration>> =
        settings.settings
            .map { snapshot -> snapshot.mcpServers }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = settings.settings.value.mcpServers,
            )

    override suspend fun update(
        transform: (Map<String, McpServerConfiguration>) -> Map<String, McpServerConfiguration>,
    ): Map<String, McpServerConfiguration> =
        settings.update { current ->
            current.copy(mcpServers = transform(current.mcpServers))
        }.mcpServers
}

/** One-time projection from Codex's decoded declaration into Kodex-owned data. */
internal fun CodexCliMcpServer.toKodexMcpConfiguration(): McpServerConfiguration =
    when (this) {
        is CodexCliMcpServer.StreamableHttp -> {
            val usesOAuth = oauth != null ||
                scopes != null ||
                oauthResource != null ||
                auth == CodexCliMcpAuth.OAuth
            McpServerConfiguration.StreamableHttp(
                url = url.trim(),
                headers = headers.mapKeys { (name, _) -> name.trim() }
                    .mapValues { (_, value) -> McpSecret(value) },
                oauth = if (usesOAuth) {
                    McpOAuthConfiguration.Uninitialized(
                        client = McpOAuthClient(
                            clientId = oauth
                                ?.clientId
                                ?.trim()
                                ?.takeIf(String::isNotEmpty),
                        ),
                        resource = oauthResource?.trim()?.takeIf(String::isNotEmpty),
                        scopes = scopes.orEmpty()
                            .map(String::trim)
                            .filter(String::isNotEmpty)
                            .distinct(),
                    )
                } else {
                    null
                },
                enabled = enabled,
            )
        }

        is CodexCliMcpServer.Stdio -> McpServerConfiguration.Stdio(
            command = command.trim(),
            args = args,
            environment = env.mapKeys { (name, _) -> name.trim() }
                .mapValues { (_, value) -> McpSecret(value) },
            workingDirectory = Path(cwd),
            enabled = enabled,
        )
    }

/** Credential-free classification adapter for one explicit Codex import. */
internal fun CodexCliMcpImportCandidate.toKodexMcpImportCandidate():
    McpCodexImportCandidate =
    when (this) {
        is CodexCliMcpImportCandidate.Supported -> McpCodexImportCandidate.Supported(
            serverName = serverName,
            configuration = configuration.toKodexMcpConfiguration(),
        )

        is CodexCliMcpImportCandidate.Unsupported -> McpCodexImportCandidate.Unsupported(
            serverName = serverName,
            transport = transport?.toKodexMcpTransportKind(),
            detail = detail,
        )
    }

private fun CodexCliMcpTransportKind.toKodexMcpTransportKind():
    io.github.stream29.kodex.mcp.contract.McpTransportKind =
    when (this) {
        CodexCliMcpTransportKind.StreamableHttp ->
            io.github.stream29.kodex.mcp.contract.McpTransportKind.StreamableHttp

        CodexCliMcpTransportKind.Stdio ->
            io.github.stream29.kodex.mcp.contract.McpTransportKind.Stdio
    }
