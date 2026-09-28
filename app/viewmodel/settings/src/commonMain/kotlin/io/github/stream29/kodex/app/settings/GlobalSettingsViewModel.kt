package io.github.stream29.kodex.app.settings

import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.cli.sessiontitle.DefaultSessionTitleModel
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.mcp.contract.*
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.osenvironment.requireUserHomeDirectory
import kotlinx.io.files.Path

/** Pure rendering/editor helpers. Backend stores and managers are not page dependencies. */
public fun AgentContextSourceSettings.withBuiltIn(source: BuiltInContextSource, enabled: Boolean): AgentContextSourceSettings =
    when (source) {
        BuiltInContextSource.AgentsHome -> copy(agentsHomeEnabled = enabled)
        BuiltInContextSource.KodexHome -> copy(kodexHomeEnabled = enabled)
        BuiltInContextSource.CodexHome -> copy(codexHomeEnabled = enabled)
        BuiltInContextSource.GitRoot -> copy(gitRootEnabled = enabled)
        BuiltInContextSource.WorkingDirectory -> copy(workingDirectoryEnabled = enabled)
    }

public fun String.toContextPath(): Path? {
    if (isBlank() || contains('$')) return null
    val home = Path(requireUserHomeDirectory())
    return when {
        this == "~" -> home
        startsWith("~/") -> Path(home, substring(2))
        else -> Path(this).takeIf(Path::isAbsolute)
    }
}
public fun staticContextSourcePaths(): Set<String> {
    val home = Path(requireUserHomeDirectory())
    return setOf(Path(home, ".agents").toString(), Path(home, ".kodex").toString(), Path(home, ".codex").toString())
}
public fun SessionTitleSettings.effectiveModel(): OpenAiModelId = model ?: DefaultSessionTitleModel

public fun McpManagedServerState.toSettingsState(): McpServerSettingsState = McpServerSettingsState(
    serverName, transport, enabled, authentication,
    when {
        !enabled -> McpServerSettingsStatus.Disabled
        authentication.isBlocked() || connection == McpClientState.AuthenticationBlocked ->
            McpServerSettingsStatus.AuthenticationBlocked(authentication)
        connection == null || connection == McpClientState.Connecting -> McpServerSettingsStatus.Connecting
        connection == McpClientState.Healthy -> McpServerSettingsStatus.Healthy(toolCount)
        connection is McpClientState.Failed -> McpServerSettingsStatus.Failed((connection as McpClientState.Failed).reason)
        connection == McpClientState.Closed -> McpServerSettingsStatus.Closed
        else -> McpServerSettingsStatus.Connecting
    },
    headerNames, environmentNames, oauth, streamableHttpUrl, stdioCommand, stdioArguments, stdioWorkingDirectory,
)
private fun McpAuthenticationState.isBlocked(): Boolean = when (this) {
    McpAuthenticationState.NotConfigured, McpAuthenticationState.Authorized -> false
    else -> true
}
