package io.github.stream29.kodex.mcp.contract

import io.github.stream29.kodex.utils.kotlinxioserialization.PathAsStringSerializer
import kotlinx.io.files.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Settings required to maintain the application-wide MCP server set. */
public interface McpSettings {
    public val mcpServers: Map<String, McpServerConfiguration>
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
        public val headers: Map<String, String> = emptyMap(),
        override val enabled: Boolean = true,
    ) : McpServerConfiguration

    /** A local MCP server launched as a direct child process. */
    @Serializable
    @SerialName("stdio")
    public data class Stdio(
        public val command: String,
        public val args: List<String> = emptyList(),
        public val environment: Map<String, String> = emptyMap(),
        /** `Path(".")` means that the child inherits the Kodex process working directory. */
        @Serializable(with = PathAsStringSerializer::class)
        @SerialName("working_directory")
        public val workingDirectory: Path = Path("."),
        override val enabled: Boolean = true,
    ) : McpServerConfiguration
}
