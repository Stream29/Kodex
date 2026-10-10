package io.github.stream29.kodex.cli.settings

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import io.github.stream29.kodex.rpc.models.CliSidebarSettings
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.utils.shellclient.Shell
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Read only known keys, resolving missing nested fields over the owner's defaults.
 * An explicit YAML null is still decoded and checked, rather than confused with
 * absence. Only nullable domain fields (such as session_title.model) accept null.
 * File codecs retain snake_case names; RPC serializers are not changed.
 */
internal fun decodeBackendSettingsFile(text: String, defaults: BackendSettings): BackendSettings {
    val root = settingsMap(text)
    return BackendSettings(
        authSource = root.value("auth_source", defaults.authSource),
        shell = root.value("shell", defaults.shell),
        contextSources = root.get<YamlMap>("context_sources")?.let { sources ->
            val base = defaults.contextSources
            AgentContextSourceSettings(
                agentsHomeEnabled = sources.value("agents_home", base.agentsHomeEnabled),
                kodexHomeEnabled = sources.value("kodex_home", base.kodexHomeEnabled),
                codexHomeEnabled = sources.value("codex_home", base.codexHomeEnabled),
                gitRootEnabled = sources.value("git_root", base.gitRootEnabled),
                workingDirectoryEnabled = sources.value("working_directory", base.workingDirectoryEnabled),
                customSources = sources.value("custom_sources", base.customSources),
            )
        } ?: defaults.contextSources,
        newSession = root.get<YamlMap>("new_session")?.let { session ->
            val base = defaults.newSession
            KodexNewSessionSettings(
                model = OpenAiModelId(session.value("model", base.model.value)),
                reasoningEffort = session.value("reasoning_effort", base.reasoningEffort),
                serviceTier = decodeServiceTier(session.value("service_tier", base.serviceTier.requestValue)),
                requestUserInputMode = session.value("request_user_input_mode", base.requestUserInputMode),
            )
        } ?: defaults.newSession,
        sessionTitle = root.get<YamlMap>("session_title")?.let { title ->
            val base = defaults.sessionTitle
            SessionTitleSettings(
                enabled = title.value("enabled", base.enabled),
                model = title.value("model", base.model?.value)?.let(::OpenAiModelId),
                reasoningEffort = title.value("reasoning_effort", base.reasoningEffort),
            )
        } ?: defaults.sessionTitle,
        mcpServers = root.value("mcp_servers", defaults.mcpServers),
    )
}

internal fun decodeCliFrontendSettingsFile(text: String, defaults: CliFrontendSettings): CliFrontendSettings {
    val root = settingsMap(text)
    return CliFrontendSettings(
        newLineKey = root.value("new_line_key", defaults.newLineKey),
        sidebars = root.get<YamlMap>("sidebars")?.let { sidebars ->
            CliSidebarSettings(
                left = sidebars.value("left", defaults.sidebars.left),
                right = sidebars.value("right", defaults.sidebars.right),
            )
        } ?: defaults.sidebars,
        hooks = root.value("hooks", defaults.hooks),
    )
}

internal fun encodeBackendSettingsFile(value: BackendSettings): String =
    SplitSettingsYaml.encodeToString(
        BackendFile.serializer(),
        BackendFile(
            authSource = value.authSource,
            shell = value.shell,
            contextSources = ContextSourcesFile(
                agentsHome = value.contextSources.agentsHomeEnabled,
                kodexHome = value.contextSources.kodexHomeEnabled,
                codexHome = value.contextSources.codexHomeEnabled,
                gitRoot = value.contextSources.gitRootEnabled,
                workingDirectory = value.contextSources.workingDirectoryEnabled,
                customSources = value.contextSources.customSources,
            ),
            newSession = NewSessionDefaultsFile(
                model = value.newSession.model.value,
                reasoningEffort = value.newSession.reasoningEffort,
                serviceTier = value.newSession.serviceTier.requestValue,
                requestUserInputMode = value.newSession.requestUserInputMode,
            ),
            sessionTitle = TitleDefaultsFile(
                enabled = value.sessionTitle.enabled,
                model = value.sessionTitle.model?.value,
                reasoningEffort = value.sessionTitle.reasoningEffort,
            ),
            mcpServers = value.mcpServers,
        ),
    )

internal fun encodeCliFrontendSettingsFile(value: CliFrontendSettings): String =
    SplitSettingsYaml.encodeToString(
        FrontendFile.serializer(),
        FrontendFile(value.newLineKey, value.sidebars, value.hooks),
    )

private fun settingsMap(text: String): YamlMap {
    val root = SplitSettingsYaml.parseToYamlNode(text)
    require(root is YamlMap) { "Settings must be a YAML mapping." }
    return root
}

private inline fun <reified T> YamlMap.value(name: String, default: T): T {
    val node = get<YamlNode>(name) ?: return default
    return SplitSettingsYaml.decodeFromYamlNode<T>(node)
}

private fun decodeServiceTier(value: String): ServiceTier = when (value) {
    "default" -> ServiceTier.Default
    "fast", "priority" -> ServiceTier.Fast
    "flex" -> ServiceTier.Flex
    "ultrafast" -> ServiceTier.Ultrafast
    else -> throw IllegalArgumentException("Unsupported service tier '$value'.")
}

@Serializable
private data class BackendFile(
    @SerialName("auth_source") val authSource: KodexAuthSource,
    val shell: Shell,
    @SerialName("context_sources") val contextSources: ContextSourcesFile,
    @SerialName("new_session") val newSession: NewSessionDefaultsFile,
    @SerialName("session_title") val sessionTitle: TitleDefaultsFile,
    @SerialName("mcp_servers") val mcpServers: Map<String, McpServerConfiguration>,
)

@Serializable
private data class ContextSourcesFile(
    @SerialName("agents_home") val agentsHome: Boolean,
    @SerialName("kodex_home") val kodexHome: Boolean,
    @SerialName("codex_home") val codexHome: Boolean,
    @SerialName("git_root") val gitRoot: Boolean,
    @SerialName("working_directory") val workingDirectory: Boolean,
    @SerialName("custom_sources") val customSources: List<AgentContextCustomSource>,
)

@Serializable
private data class NewSessionDefaultsFile(
    val model: String,
    @SerialName("reasoning_effort") val reasoningEffort: ReasoningEffort,
    @SerialName("service_tier") val serviceTier: String,
    @SerialName("request_user_input_mode") val requestUserInputMode: RequestUserInputMode,
)

/** Null model selects the built-in title generator model, even with non-null owner defaults. */
@Serializable
private data class TitleDefaultsFile(
    val enabled: Boolean,
    val model: String?,
    @SerialName("reasoning_effort") val reasoningEffort: ReasoningEffort,
)

@Serializable
private data class FrontendFile(
    @SerialName("new_line_key") val newLineKey: NewLineKey,
    val sidebars: CliSidebarSettings,
    val hooks: List<NotificationHook>,
)

private val SplitSettingsYaml = Yaml(
    configuration = YamlConfiguration(
        encodeDefaults = true,
        strictMode = false,
        polymorphismStyle = PolymorphismStyle.Property,
        polymorphismPropertyName = "type",
        singleLineStringStyle = SingleLineStringStyle.PlainExceptAmbiguous,
    ),
)
