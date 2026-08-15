package io.github.stream29.kodex.cli.settings

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import io.github.stream29.kodex.hook.contract.HookConfiguration
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.openai.AgentMode
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.Shell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Kodex-owned global settings persisted as one complete private snapshot.
 *
 * Regular loading never reads Codex `config.toml`, Hook files, or MCP
 * declarations. The selected Codex Home remains data used by authentication and
 * explicit import commands only.
 */
public class KodexSettingsStore private constructor(
    private val fileSystem: CoroutineFileSystem,
    private val settingsDirectory: Path,
    private val defaults: KodexGlobalSettings,
    initialSettings: KodexGlobalSettings,
) : KodexGlobalSettingsStore {
    private val updateMutex = Mutex()

    /** Kodex-owned settings file under the caller-selected private directory. */
    public val settingsPath: Path
        get() = Path(settingsDirectory, KodexSettingsFileName)

    override val settings: StateFlow<KodexGlobalSettings>
        field = MutableStateFlow(initialSettings)

    override suspend fun update(
        transform: (KodexGlobalSettings) -> KodexGlobalSettings,
    ): KodexGlobalSettings = updateMutex.withLock {
        val current = readFileOrNull()?.toSettings(defaults) ?: defaults
        val updated = transform(current)
        writeFile(GlobalSettingsFile.from(updated))
        updated.also { settings.value = it }
    }

    /** Reloads only Kodex's private settings snapshot. */
    override suspend fun reload(): KodexGlobalSettings = updateMutex.withLock {
        val stored = readFileOrNull()
        val resolved = stored?.toSettings(defaults) ?: defaults
        settings.value = resolved
        resolved
    }

    private suspend fun readFileOrNull(): GlobalSettingsFile? {
        if (!fileSystem.exists(settingsPath)) return null
        val text = fileSystem.readString(settingsPath)
        return try {
            SettingsYaml.decodeFromString(GlobalSettingsFile.serializer(), text)
        } catch (error: Exception) {
            throw invalidSettings(error)
        }
    }

    private fun invalidSettings(cause: Exception): IllegalArgumentException =
        IllegalArgumentException(
            "Kodex global settings must be valid YAML at $settingsPath.",
            cause,
        )

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun writeFile(value: GlobalSettingsFile) {
        val path = settingsPath
        fileSystem.createDirectories(settingsDirectory)
        val temporary = Path(settingsDirectory, ".${path.name}.${Uuid.generateV7()}.tmp")
        try {
            val contents = SettingsYaml.encodeToString(GlobalSettingsFile.serializer(), value) + "\n"
            fileSystem.writePrivateString(temporary, contents, mustCreate = true)
            fileSystem.atomicMove(temporary, path)
        } finally {
            fileSystem.delete(temporary, mustExist = false)
        }
    }

    internal companion object {
        suspend fun open(
            settingsDirectory: Path,
            defaults: KodexGlobalSettings,
            fileSystem: CoroutineFileSystem,
        ): KodexSettingsStore =
            KodexSettingsStore(
                fileSystem = fileSystem,
                settingsDirectory = settingsDirectory,
                defaults = defaults,
                initialSettings = defaults,
            ).also { store -> store.reload() }
    }
}

/**
 * Opens Kodex-owned global settings.
 */
public suspend fun openGlobalSettings(
    settingsDirectory: Path,
    defaults: KodexGlobalSettings,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): KodexSettingsStore =
    KodexSettingsStore.open(
        settingsDirectory = settingsDirectory,
        defaults = defaults,
        fileSystem = fileSystem,
    )

/** Tolerant settings file decoded over the current application defaults. */
@Serializable
private data class GlobalSettingsFile(
    @SerialName("codex_home")
    val codexHome: String? = null,
    @SerialName("auth_source")
    val authSource: KodexAuthSource? = null,
    val shell: Shell? = null,
    @SerialName("new_line_key")
    val newLineKey: NewLineKey? = null,
    @SerialName("new_session")
    val newSession: NewSessionFile? = null,
    @SerialName("session_title")
    val sessionTitle: SessionTitleFile? = null,
    @SerialName("mcp_servers")
    val mcpServers: Map<String, McpServerConfiguration>? = null,
    val hooks: HookConfiguration? = null,
) {
    fun toSettings(defaults: KodexGlobalSettings): KodexGlobalSettings =
        defaults.copy(
            codexHome = codexHome?.let(::Path) ?: defaults.codexHome,
            authSource = authSource ?: defaults.authSource,
            shell = shell ?: defaults.shell,
            newLineKey = newLineKey ?: defaults.newLineKey,
            newSession = newSession?.applyTo(defaults.newSession) ?: defaults.newSession,
            sessionTitle = sessionTitle?.applyTo(defaults.sessionTitle) ?: defaults.sessionTitle,
            mcpServers = mcpServers ?: defaults.mcpServers,
            hooks = hooks ?: defaults.hooks,
        )

    companion object {
        fun from(settings: KodexGlobalSettings): GlobalSettingsFile =
            GlobalSettingsFile(
                codexHome = settings.codexHome.toString(),
                authSource = settings.authSource,
                shell = settings.shell,
                newLineKey = settings.newLineKey,
                newSession = NewSessionFile.from(settings.newSession),
                sessionTitle = SessionTitleFile.from(settings.sessionTitle),
                mcpServers = settings.mcpServers,
                hooks = settings.hooks,
            )
    }
}

@Serializable
private data class NewSessionFile(
    val model: String? = null,
    @SerialName("reasoning_effort")
    val reasoningEffort: ReasoningEffort? = null,
    @SerialName("service_tier")
    val serviceTier: String? = null,
    @SerialName("agent_mode")
    val agentMode: AgentMode? = null,
    @SerialName("request_user_input_mode")
    val requestUserInputMode: RequestUserInputMode? = null,
) {
    fun applyTo(defaults: KodexNewSessionSettings): KodexNewSessionSettings =
        defaults.copy(
            model = model?.let(::OpenAiModelId) ?: defaults.model,
            reasoningEffort = reasoningEffort ?: defaults.reasoningEffort,
            serviceTier = serviceTier?.toServiceTier() ?: defaults.serviceTier,
            agentMode = agentMode ?: defaults.agentMode,
            requestUserInputMode = requestUserInputMode ?: defaults.requestUserInputMode,
        )

    companion object {
        fun from(settings: KodexNewSessionSettings): NewSessionFile =
            NewSessionFile(
                model = settings.model.value,
                reasoningEffort = settings.reasoningEffort,
                serviceTier = settings.serviceTier.requestValue,
                agentMode = settings.agentMode,
                requestUserInputMode = settings.requestUserInputMode,
            )
    }
}

@Serializable
private data class SessionTitleFile(
    val enabled: Boolean? = null,
    val model: String? = null,
    @SerialName("reasoning_effort")
    val reasoningEffort: ReasoningEffort? = null,
) {
    fun applyTo(defaults: SessionTitleSettings): SessionTitleSettings =
        defaults.copy(
            enabled = enabled ?: defaults.enabled,
            model = model?.let(::OpenAiModelId) ?: defaults.model,
            reasoningEffort = reasoningEffort ?: defaults.reasoningEffort,
        )

    companion object {
        fun from(settings: SessionTitleSettings): SessionTitleFile =
            SessionTitleFile(
                enabled = settings.enabled,
                model = settings.model?.value,
                reasoningEffort = settings.reasoningEffort,
            )
    }
}

private fun String.toServiceTier(): ServiceTier =
    when (this) {
        "default" -> ServiceTier.Default
        "fast", "priority" -> ServiceTier.Fast
        "flex" -> ServiceTier.Flex
        else -> throw IllegalArgumentException(
            "Unsupported service tier '$this' in settings.yml.",
        )
    }

private val SettingsYaml = Yaml(
    configuration = YamlConfiguration(
        encodeDefaults = false,
        strictMode = false,
        polymorphismStyle = PolymorphismStyle.Property,
        polymorphismPropertyName = "type",
        singleLineStringStyle = SingleLineStringStyle.PlainExceptAmbiguous,
    ),
)

private const val KodexSettingsFileName: String = "settings.yml"
