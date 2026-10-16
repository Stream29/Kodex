package io.github.stream29.kodex.mcp.contract

/** Pure editor validation shared by local and RPC frontends; this performs no manager command. */
public fun McpServerDraft.validatedName(): String = serverName.trim().also {
    require(it.isNotEmpty()) { "An MCP server name must not be blank." }
}

/** Keeps existing secrets only when explicitly requested, without synthesizing credentials. */
public fun McpServerDraft.toConfiguration(
    existing: McpServerConfiguration?,
    preserveOAuth: Boolean,
): McpServerConfiguration = when (this) {
    is McpServerDraft.StreamableHttp -> {
        val previous = existing as? McpServerConfiguration.StreamableHttp
        val url = configuration.url.trim()
        require(url.isNotEmpty()) { "An MCP Streamable HTTP URL must not be blank." }
        McpServerConfiguration.StreamableHttp(
            url = url,
            headers = configuration.headers.resolveSecrets(previous?.headers.orEmpty(), "header"),
            oauth = configuration.oauth?.toConfiguration(previous?.oauth, preserveOAuth && previous?.url == url),
            enabled = enabled,
        )
    }
    is McpServerDraft.Stdio -> {
        val previous = existing as? McpServerConfiguration.Stdio
        val command = configuration.command.trim()
        require(command.isNotEmpty()) { "An MCP stdio command must not be blank." }
        McpServerConfiguration.Stdio(
            command = command, args = configuration.args,
            environment = configuration.environment.resolveSecrets(previous?.environment.orEmpty(), "environment"),
            workingDirectory = configuration.workingDirectory, enabled = enabled,
        )
    }
}

private fun McpOAuthDraft.toConfiguration(
    existing: McpOAuthConfiguration?,
    preserveOAuth: Boolean,
): McpOAuthConfiguration {
    val client = McpOAuthClient(
        clientId = clientId.normalizedOptional(),
        clientSecret = clientSecret.resolveOptionalSecret(existing?.client?.clientSecret, "client secret"),
        redirectUri = redirectUri.trim(),
        authorizationEndpoint = authorizationEndpoint.normalizedOptional(),
        tokenEndpoint = tokenEndpoint.normalizedOptional(),
    )
    val value = McpOAuthConfiguration.Uninitialized(
        client = client, resource = resource.normalizedOptional(),
        scopes = scopes.map(String::trim).filter(String::isNotEmpty).distinct(),
    )
    return if (preserveOAuth && existing is McpOAuthConfiguration.Initialized &&
        existing.client == client && existing.resource == value.resource && existing.scopes == value.scopes
    ) existing.copy(client = client, resource = value.resource, scopes = value.scopes) else value
}

private fun Map<String, McpSecretDraft>.resolveSecrets(
    existing: Map<String, McpSecret>,
    kind: String,
): Map<String, McpSecret> {
    val drafts = this
    return buildMap {
        drafts.forEach { (rawName, draft) ->
            val name = rawName.trim()
            require(name.isNotEmpty()) { "An MCP $kind name must not be blank." }
            require(name !in this) { "MCP $kind names must be unique." }
            val secret = when (draft) {
                McpSecretDraft.Keep -> existing[name]
                    ?: throw IllegalArgumentException("MCP $kind '$name' has no existing value to retain.")
                is McpSecretDraft.Replace -> McpSecret(draft.value)
            }
            put(name, secret)
        }
    }
}

private fun McpSecretDraft?.resolveOptionalSecret(existing: McpSecret?, kind: String): McpSecret? = when (this) {
    null -> null
    McpSecretDraft.Keep -> existing
        ?: throw IllegalArgumentException("The MCP $kind has no existing value to retain.")
    is McpSecretDraft.Replace -> McpSecret(value)
}

private fun String?.normalizedOptional(): String? = this?.trim()?.takeIf(String::isNotEmpty)
