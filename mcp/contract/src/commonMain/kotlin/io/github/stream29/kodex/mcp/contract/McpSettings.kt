package io.github.stream29.kodex.mcp.contract

import io.github.stream29.kodex.utils.kotlinxioserialization.PathAsStringSerializer
import kotlinx.io.files.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Settings required to maintain the application-wide MCP server set. */
public interface McpSettings {
    public val mcpServers: Map<String, McpServerConfiguration>
}

/**
 * A persisted MCP secret whose diagnostic rendering never reveals its value.
 *
 * The inline serializer keeps the existing YAML/TOML-like string shape while
 * the dedicated type prevents data-class, collection, and exception rendering
 * from accidentally printing credentials.
 */
@JvmInline
@Serializable
public value class McpSecret(
    public val value: String,
) {
    override fun toString(): String = RedactedMcpSecret
}

/** OAuth client identity and browser callback configuration. */
@Serializable
public data class McpOAuthClient(
    @SerialName("client_id")
    public val clientId: String? = null,
    @SerialName("client_secret")
    public val clientSecret: McpSecret? = null,
    @SerialName("redirect_uri")
    public val redirectUri: String = DefaultMcpOAuthRedirectUri,
    @SerialName("authorization_endpoint")
    public val authorizationEndpoint: String? = null,
    @SerialName("token_endpoint")
    public val tokenEndpoint: String? = null,
) {
    init {
        require(clientId == null || clientId.isNotBlank()) {
            "An MCP OAuth client id must be absent or non-blank."
        }
        require(clientId != null || clientSecret == null) {
            "An MCP OAuth client secret requires a client id."
        }
        require(redirectUri.isNotBlank()) { "An MCP OAuth redirect URI must not be blank." }
    }
}

/** Persisted OAuth state for one Streamable HTTP server. */
@Serializable
public sealed interface McpOAuthConfiguration {
    public val client: McpOAuthClient
    public val resource: String?
    public val scopes: List<String>

    /** Client metadata is configured, but browser authorization has not completed. */
    @Serializable
    @SerialName("uninitialized")
    public data class Uninitialized(
        override val client: McpOAuthClient,
        override val resource: String? = null,
        override val scopes: List<String> = emptyList(),
    ) : McpOAuthConfiguration

    /** Browser authorization completed and its latest token set is persisted. */
    @Serializable
    @SerialName("initialized")
    public data class Initialized(
        override val client: McpOAuthClient,
        override val resource: String? = null,
        override val scopes: List<String> = emptyList(),
        @SerialName("resolved_authorization_endpoint")
        public val resolvedAuthorizationEndpoint: String,
        @SerialName("resolved_token_endpoint")
        public val resolvedTokenEndpoint: String,
        @SerialName("token_endpoint_auth_method")
        public val tokenEndpointAuthMethod: McpOAuthTokenEndpointAuthMethod =
            McpOAuthTokenEndpointAuthMethod.ClientSecretPost,
        @SerialName("access_token")
        public val accessToken: McpSecret,
        @SerialName("refresh_token")
        public val refreshToken: McpSecret? = null,
        @SerialName("token_type")
        public val tokenType: String = "Bearer",
        @SerialName("expires_at_epoch_seconds")
        public val expiresAtEpochSeconds: Long? = null,
    ) : McpOAuthConfiguration {
        init {
            require(client.clientId != null) {
                "An initialized MCP OAuth configuration must have a client id."
            }
            require(resolvedAuthorizationEndpoint.isNotBlank()) {
                "An initialized MCP OAuth authorization endpoint must not be blank."
            }
            require(resolvedTokenEndpoint.isNotBlank()) {
                "An initialized MCP OAuth token endpoint must not be blank."
            }
            require(tokenType.isNotBlank()) { "An MCP OAuth token type must not be blank." }
        }
    }
}

/** OAuth token-endpoint client authentication selected during discovery. */
@Serializable
public enum class McpOAuthTokenEndpointAuthMethod {
    @SerialName("client_secret_basic")
    ClientSecretBasic,

    @SerialName("client_secret_post")
    ClientSecretPost,

    @SerialName("none")
    None,
}

/** Configuration for one application-wide MCP server. */
@Serializable
public sealed interface McpServerConfiguration {
    public val enabled: Boolean

    /** A server reached through MCP Streamable HTTP. */
    @Serializable
    @SerialName("streamable_http")
    public data class StreamableHttp(
        public val url: String,
        public val headers: Map<String, McpSecret> = emptyMap(),
        public val oauth: McpOAuthConfiguration? = null,
        override val enabled: Boolean = true,
    ) : McpServerConfiguration {
        init {
            require(url.isNotBlank()) { "An MCP Streamable HTTP URL must not be blank." }
        }
    }

    /** A local MCP server launched as a direct child process. */
    @Serializable
    @SerialName("stdio")
    public data class Stdio(
        public val command: String,
        public val args: List<String> = emptyList(),
        public val environment: Map<String, McpSecret> = emptyMap(),
        /** `Path(".")` means that the child inherits the Kodex process working directory. */
        @Serializable(with = PathAsStringSerializer::class)
        @SerialName("working_directory")
        public val workingDirectory: Path = Path("."),
        override val enabled: Boolean = true,
    ) : McpServerConfiguration {
        init {
            require(command.isNotBlank()) { "An MCP stdio command must not be blank." }
        }
    }
}

public const val DefaultMcpOAuthRedirectUri: String = "http://127.0.0.1:8765/callback"

private const val RedactedMcpSecret: String = "<redacted>"
