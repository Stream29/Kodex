package io.github.stream29.kodex.openai.codexclistorage

import dev.eav.tomlkt.Toml
import dev.eav.tomlkt.TomlTable
import io.github.stream29.kodex.hook.contract.HookCodexImportCandidate
import io.github.stream29.kodex.hook.contract.HookCodexSourceKind
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

public class CodexCliStorage(
    internal val directory: Path,
    internal val fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
) {
    private val authPath: Path = Path(directory, CodexAuthFileName)
    private val configPath: Path = Path(directory, CodexConfigFileName)

    /**
     * @return Nullable because Codex CLI may not be signed in; `null` means
     * `auth.json` is absent.
     */
    public suspend fun readAuthOrNull(): CodexAuthJson? {
        val text = authPath.readTextOrNull(fileSystem) ?: return null
        return OpenAiJsonCodec.decodeFromString(CodexAuthJson.serializer(), text)
    }

    /**
     * Reads and classifies every Codex MCP declaration for an explicit import.
     *
     * A declaration is supported only when all of its fields can be preserved
     * by Kodex. Unsupported previews expose field names, never their values.
     */
    public suspend fun readMcpImportCandidates(): List<CodexCliMcpImportCandidate> {
        val text = configPath.readTextOrNull(fileSystem) ?: return emptyList()
        val root = CodexConfigToml.parseToTomlTable(text)
        val servers = root["mcp_servers"] as? TomlTable ?: return emptyList()
        return servers.entries
            .sortedBy(Map.Entry<String, dev.eav.tomlkt.TomlElement>::key)
            .map { (serverName, element) ->
                classifyMcpImportCandidate(serverName, element as? TomlTable)
            }
    }

    /**
     * Reads this directory's Hook files only for an explicit import operation.
     *
     * Each existing `hooks.json`, and each `config.toml` containing a `hooks`
     * table, becomes one independently classified source. Supported commands
     * are platform-selected, environment-expanded, timeout-normalized, and
     * matcher-compiled before being returned.
     */
    public suspend fun readHookImportCandidates(
        sourceKind: HookCodexSourceKind,
        environment: Map<String, String> = emptyMap(),
    ): List<HookCodexImportCandidate> =
        readHookImportCandidatesInternal(sourceKind, environment)
}

private fun classifyMcpImportCandidate(
    serverName: String,
    table: TomlTable?,
): CodexCliMcpImportCandidate {
    if (table == null) {
        return CodexCliMcpImportCandidate.Unsupported(
            serverName = serverName,
            transport = null,
            detail = "The declaration is not a server table.",
        )
    }
    val hasCommand = "command" in table
    val hasUrl = "url" in table
    val transport = when {
        hasCommand && !hasUrl -> CodexCliMcpTransportKind.Stdio
        hasUrl && !hasCommand -> CodexCliMcpTransportKind.StreamableHttp
        else -> null
    }
    if (transport == null) {
        return CodexCliMcpImportCandidate.Unsupported(
            serverName = serverName,
            transport = null,
            detail = "The declaration must contain exactly one supported transport.",
        )
    }
    val supportedFields = when (transport) {
        CodexCliMcpTransportKind.StreamableHttp -> SupportedHttpMcpImportFields
        CodexCliMcpTransportKind.Stdio -> SupportedStdioMcpImportFields
    }
    val unsupportedFields = (table.keys - supportedFields).sorted()
    if (unsupportedFields.isNotEmpty()) {
        return CodexCliMcpImportCandidate.Unsupported(
            serverName = serverName,
            transport = transport,
            detail = "Unsupported fields: ${unsupportedFields.joinToString()}.",
        )
    }
    val configuration = runCatching {
        CodexConfigToml.decodeFromTomlElement(CodexCliMcpServer.serializer(), table)
    }.getOrElse {
        return CodexCliMcpImportCandidate.Unsupported(
            serverName = serverName,
            transport = transport,
            detail = "The supported transport fields are invalid.",
        )
    }
    val invalidSupportedFields = when (configuration) {
        is CodexCliMcpServer.StreamableHttp ->
            configuration.url.isBlank() || !configuration.headers.keys.haveValidImportNames()

        is CodexCliMcpServer.Stdio ->
            configuration.command.isBlank() || !configuration.env.keys.haveValidImportNames()
    }
    if (invalidSupportedFields) {
        return CodexCliMcpImportCandidate.Unsupported(
            serverName = serverName,
            transport = transport,
            detail = "The supported transport fields are invalid.",
        )
    }
    if (configuration is CodexCliMcpServer.StreamableHttp) {
        val oauthTable = table["oauth"] as? TomlTable
        val unsupportedOAuthFields = oauthTable
            ?.keys
            .orEmpty()
            .minus(SupportedOAuthMcpImportFields)
            .sorted()
        if (unsupportedOAuthFields.isNotEmpty()) {
            return CodexCliMcpImportCandidate.Unsupported(
                serverName = serverName,
                transport = transport,
                detail = "Unsupported OAuth fields: ${unsupportedOAuthFields.joinToString()}.",
            )
        }
        if (configuration.auth == CodexCliMcpAuth.ChatGpt) {
            return CodexCliMcpImportCandidate.Unsupported(
                serverName = serverName,
                transport = transport,
                detail = "Unsupported fields: auth.",
            )
        }
        val needsOAuthClient = configuration.oauth != null ||
            configuration.scopes != null ||
            configuration.oauthResource != null ||
            configuration.auth == CodexCliMcpAuth.OAuth
        if (needsOAuthClient && configuration.oauth?.clientId.isNullOrBlank()) {
            return CodexCliMcpImportCandidate.Unsupported(
                serverName = serverName,
                transport = transport,
                detail = "Dynamic OAuth client registration is not supported.",
            )
        }
    }
    return CodexCliMcpImportCandidate.Supported(
        serverName = serverName,
        configuration = configuration,
    )
}

private fun Set<String>.haveValidImportNames(): Boolean =
    all(String::isNotBlank) && map(String::trim).distinct().size == size

private suspend fun Path.readTextOrNull(fileSystem: CoroutineFileSystem): String? {
    if (!fileSystem.exists(this)) return null
    return fileSystem.readString(this)
}

private const val CodexAuthFileName: String = "auth.json"
private const val CodexConfigFileName: String = "config.toml"

private val SupportedHttpMcpImportFields: Set<String> =
    setOf(
        "url",
        "http_headers",
        "auth",
        "scopes",
        "oauth",
        "oauth_resource",
        "enabled",
    )
private val SupportedStdioMcpImportFields: Set<String> =
    setOf("command", "args", "env", "cwd", "enabled")
private val SupportedOAuthMcpImportFields: Set<String> = setOf("client_id")

private val CodexConfigToml: Toml = Toml {
    ignoreUnknownKeys = true
}
