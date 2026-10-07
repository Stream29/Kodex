package io.github.stream29.kodex.rpc.models

import io.github.stream29.kodex.cli.settings.KodexAuthSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Credential destination bound by the backend when preparing a login, not on each callback. */
@Serializable
public sealed interface OAuthTarget {
    @Serializable
    @SerialName("openai")
    public data class OpenAi(public val source: KodexAuthSource) : OAuthTarget

    @Serializable
    @SerialName("mcp")
    public data class Mcp(public val serverName: String) : OAuthTarget {
        init {
            require(serverName.isNotBlank()) { "An MCP OAuth target must have a server name." }
        }
    }
}
