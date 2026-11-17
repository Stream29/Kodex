package io.github.stream29.kodex.app.mcpsettings

import io.github.stream29.kodex.mcp.contract.DefaultMcpOAuthRedirectUri
import io.github.stream29.kodex.mcp.contract.McpOAuthDraft
import io.github.stream29.kodex.mcp.contract.McpSecretDraft
import io.github.stream29.kodex.mcp.contract.McpServerDraft
import io.github.stream29.kodex.mcp.contract.McpStdioDraft
import io.github.stream29.kodex.mcp.contract.McpStreamableHttpDraft
import io.github.stream29.kodex.mcp.contract.McpTransportKind
import kotlinx.io.files.Path

/** Baseline renderer parsers moved intact to the business owner; no secret resolution occurs here. */
internal fun McpEditorDraft.validatedDraft(): McpServerDraft {
    val serverName = name.trim()
    require(serverName.isNotEmpty()) { "Server name is required." }
    val endpointValue = when (transport) {
        McpTransportKind.StreamableHttp -> httpUrl.trim()
        McpTransportKind.Stdio -> command.trim()
    }
    require(endpointValue.isNotEmpty()) { "A URL or command is required." }
    return when (transport) {
        McpTransportKind.StreamableHttp -> McpServerDraft.StreamableHttp(
            serverName = serverName,
            enabled = enabled,
            configuration = McpStreamableHttpDraft(
                url = endpointValue,
                headers = parseSecretEntries(headers),
                oauth = if (oauthEnabled) {
                    val redirectUri = oauthRedirect.trim()
                    require(redirectUri.isNotEmpty()) { "OAuth redirect URI is required." }
                    McpOAuthDraft(
                        clientId = oauthClientId.trim().ifEmpty { null },
                        clientSecret = oauthClientSecret.toOptionalSecretDraft(),
                        redirectUri = redirectUri,
                        authorizationEndpoint = oauthAuthorizationEndpoint.trim().ifEmpty { null },
                        tokenEndpoint = oauthTokenEndpoint.trim().ifEmpty { null },
                        resource = oauthResource.trim().ifEmpty { null },
                        scopes = oauthScopes.split(',').map(String::trim).filter(String::isNotEmpty),
                    )
                } else null,
            ),
        )
        McpTransportKind.Stdio -> McpServerDraft.Stdio(
            serverName = serverName,
            enabled = enabled,
            configuration = McpStdioDraft(
                command = endpointValue,
                args = parseArguments(arguments),
                environment = parseSecretEntries(environment),
                workingDirectory = Path(workingDirectory.trim().ifEmpty { "." }),
            ),
        )
    }
}

internal fun McpServerDraft?.editorFields(): McpEditorDraft {
    if (this == null) return McpEditorDraft()
    val http = (this as? McpServerDraft.StreamableHttp)?.configuration
    val stdio = (this as? McpServerDraft.Stdio)?.configuration
    val oauth = http?.oauth
    return McpEditorDraft(
        name = serverName, enabled = enabled,
        transport = if (stdio != null) McpTransportKind.Stdio else McpTransportKind.StreamableHttp,
        httpUrl = http?.url.orEmpty(), command = stdio?.command.orEmpty(),
        arguments = stdio?.args?.formatArguments().orEmpty(),
        headers = http?.headers?.formatSecrets().orEmpty(),
        environment = stdio?.environment?.formatSecrets().orEmpty(),
        workingDirectory = stdio?.workingDirectory?.toString() ?: ".",
        oauthEnabled = oauth != null,
        oauthClientId = oauth?.clientId.orEmpty(),
        oauthClientSecret = oauth?.clientSecret?.editorText().orEmpty(),
        oauthRedirect = oauth?.redirectUri ?: DefaultMcpOAuthRedirectUri,
        oauthAuthorizationEndpoint = oauth?.authorizationEndpoint.orEmpty(),
        oauthTokenEndpoint = oauth?.tokenEndpoint.orEmpty(),
        oauthResource = oauth?.resource.orEmpty(),
        oauthScopes = oauth?.scopes?.joinToString(",").orEmpty(),
    )
}

private fun McpSecretDraft.editorText(): String = when (this) {
    McpSecretDraft.Keep -> KeepValueMarker
    is McpSecretDraft.Replace -> value
}

private fun Map<String, McpSecretDraft>.formatSecrets(): String =
    entries.joinToString(";") { (name, value) -> "$name=${value.editorText()}" }

private fun parseSecretEntries(text: String): Map<String, McpSecretDraft> =
    buildMap {
        text.split(';').map(String::trim).filter(String::isNotEmpty).forEach { entry ->
            val separator = entry.indexOf('=')
            require(separator > 0) { "Each secret entry must use KEY=value." }
            val name = entry.substring(0, separator).trim()
            val value = entry.substring(separator + 1)
            require(name.isNotEmpty()) { "A secret entry name must not be blank." }
            require(name !in this) { "Secret entry names must be unique." }
            put(name, if (value == KeepValueMarker) McpSecretDraft.Keep else McpSecretDraft.Replace(value))
        }
    }

private fun List<String>.formatArguments(): String =
    joinToString(" ") { argument ->
        if (argument.isNotEmpty() && argument.none { it.isWhitespace() || it in "\"'\\" }) {
            argument
        } else {
            buildString {
                append('"')
                argument.forEach { character ->
                    if (character == '"' || character == '\\') append('\\')
                    append(character)
                }
                append('"')
            }
        }
    }

private fun parseArguments(text: String): List<String> {
    val arguments = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    var tokenStarted = false
    fun finishToken() {
        if (!tokenStarted) return
        arguments += current.toString()
        current.clear()
        tokenStarted = false
    }
    text.forEach { character ->
        when {
            escaped -> {
                current.append(character)
                tokenStarted = true
                escaped = false
            }
            character == '\\' -> {
                escaped = true
                tokenStarted = true
            }
            quote != null && character == quote -> quote = null
            quote != null -> {
                current.append(character)
                tokenStarted = true
            }
            character == '"' || character == '\'' -> {
                quote = character
                tokenStarted = true
            }
            character.isWhitespace() -> finishToken()
            else -> {
                current.append(character)
                tokenStarted = true
            }
        }
    }
    require(!escaped) { "An argument cannot end with an escape character." }
    require(quote == null) { "An argument quote is not closed." }
    finishToken()
    return arguments
}

private fun String.toOptionalSecretDraft(): McpSecretDraft? = when (this) {
    "" -> null
    KeepValueMarker -> McpSecretDraft.Keep
    else -> McpSecretDraft.Replace(this)
}

private const val KeepValueMarker: String = "<keep>"
