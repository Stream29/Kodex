package io.github.stream29.kodex.mcp.impl

import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration

/**
 * Validates complete proposals at the persistence boundary. Unlike draft
 * editing this does not normalize values or silently change the CAS update.
 * Runtime credentials cannot be transplanted to a new server identity.
 */
public fun validateMcpConfigurationUpdate(
    current: Map<String, McpServerConfiguration>,
    update: Map<String, McpServerConfiguration>,
) {
    validateNames(update.keys, "server")
    update.forEach { (name, configuration) ->
        if (configuration == current[name]) return@forEach
        when (configuration) {
            is McpServerConfiguration.Stdio -> {
                require(configuration.command.isNotBlank()) { "An MCP stdio command must not be blank." }
                validateNames(configuration.environment.keys, "environment")
            }
            is McpServerConfiguration.StreamableHttp -> {
                require(configuration.url.isNotBlank()) { "An MCP Streamable HTTP URL must not be blank." }
                validateNames(configuration.headers.keys, "header")
                val oauth = configuration.oauth
                if (oauth is McpOAuthConfiguration.Initialized) {
                    val previous = current[name] as? McpServerConfiguration.StreamableHttp
                    val previousOAuth = previous?.oauth as? McpOAuthConfiguration.Initialized
                    require(
                        previous?.url == configuration.url &&
                            previousOAuth != null &&
                            previousOAuth.client == oauth.client &&
                            previousOAuth.resource == oauth.resource &&
                            previousOAuth.scopes == oauth.scopes
                    ) { "MCP credentials must be reauthorized after a server identity change." }
                }
            }
        }
    }
}

private fun validateNames(names: Set<String>, kind: String) {
    val normalized = names.map(String::trim)
    require(normalized.none(String::isEmpty)) { "An MCP $kind name must not be blank." }
    require(normalized.distinct().size == normalized.size) { "MCP $kind names must be unique." }
}
