package io.github.stream29.kodex.cli.settings

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import io.github.stream29.kodex.hook.contract.HookConfiguration
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.openai.codexclistorage.CodexCliConfig
import io.github.stream29.kodex.openai.codexclistorage.CodexCliHookSourceKind
import io.github.stream29.kodex.openai.codexclistorage.CodexCliMcpServer
import io.github.stream29.kodex.openai.codexclistorage.CodexCliStorage
import io.github.stream29.kodex.openai.ModeKind
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.Shell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Read-only Codex config inheritance plus Kodex-owned sparse overrides.
 *
 * [update] never writes Codex's `config.toml` or profile/project files. It
 * persists only [settingsPath] under the caller-owned private settings
 * directory, then publishes the new effective settings.
 */
public class KodexSettingsStore private constructor(
    private var storage: CodexCliStorage,
    private var codexHome: Path,
    private val fileSystem: CoroutineFileSystem,
    private val settingsDirectory: Path,
    private val workingDirectory: Path,
    private val defaults: KodexGlobalSettings,
    initialSettings: KodexGlobalSettings,
) : KodexGlobalSettingsStore {
    private val updateMutex = Mutex()

    /** Kodex-owned sparse settings file, independent of [storage]. */
    public val settingsPath: Path
        get() = Path(settingsDirectory, KodexSettingsFileName)

    override val settings: StateFlow<KodexGlobalSettings>
        field = MutableStateFlow(initialSettings)

    override suspend fun update(
        transform: (KodexGlobalSettings) -> KodexGlobalSettings,
    ): KodexGlobalSettings = updateMutex.withLock {
        val persisted = readOverrideOrNull() ?: GlobalSettingsFile()
        val current = resolveSettings(persisted)
        val updated = transform(current)
        val override = persisted.withChanges(current, updated)
        writeOverride(override)
        val resolved = resolveSettings(override)
        settings.value = resolved
        resolved
    }

    /** Reloads Codex's read-only layers and the current Kodex override. */
    override suspend fun reload(): KodexGlobalSettings = updateMutex.withLock {
        resolveSettings().also { resolved ->
            settings.value = resolved
        }
    }

    private suspend fun resolveSettings(): KodexGlobalSettings {
        val override = readOverrideOrNull()
        return resolveSettings(override)
    }

    private suspend fun resolveSettings(override: GlobalSettingsFile?): KodexGlobalSettings {
        override?.codexHome?.let { home ->
            codexHome = Path(home)
            storage = CodexCliStorage(codexHome, fileSystem)
        }
        val inherited = resolveInheritedSettings()
        return override?.let(inherited::withOverride) ?: inherited
    }

    private suspend fun resolveInheritedSettings(): KodexGlobalSettings {
        val hooks = loadHookConfiguration(
            userStorage = storage,
            workingDirectory = workingDirectory,
            fileSystem = fileSystem,
        )
        val resolver = SettingsResolver(
            defaults.copy(
                codexHome = codexHome,
                hooks = hooks,
            ),
        )
        storage.readConfigTomlOrNull()?.let(resolver::apply)
        return resolver.resolve()
    }

    /**
     * @return Nullable because the private settings file may not exist; `null`
     * means Kodex contributes no overrides.
     */
    private suspend fun readOverrideOrNull(): GlobalSettingsFile? {
        if (!fileSystem.exists(settingsPath)) return null
        val text = fileSystem.readString(settingsPath)
        val value = try {
            SettingsYaml.decodeFromString(GlobalSettingsFile.serializer(), text)
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "Kodex global settings must be valid YAML at $settingsPath.",
                error,
            )
        }
        if (value.schemaVersion != CurrentGlobalSettingsSchemaVersion) {
            throw IllegalArgumentException(
                "Unsupported Kodex global settings schema ${value.schemaVersion} at $settingsPath.",
            )
        }
        return value
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun writeOverride(value: GlobalSettingsFile) {
        val path = settingsPath
        fileSystem.createDirectories(settingsDirectory)
        val temporary = Path(settingsDirectory, ".${path.name}.${Uuid.generateV7()}.tmp")
        try {
            val contents = SettingsYaml.encodeToString(GlobalSettingsFile.serializer(), value) + "\n"
            fileSystem.writeString(temporary, contents, mustCreate = true)
            fileSystem.atomicMove(temporary, path)
        } finally {
            fileSystem.delete(temporary, mustExist = false)
        }
    }

    internal companion object {
        suspend fun open(
            storage: CodexCliStorage,
            settingsDirectory: Path,
            workingDirectory: Path,
            defaults: KodexGlobalSettings,
            fileSystem: CoroutineFileSystem,
        ): KodexSettingsStore {
            return KodexSettingsStore(
                storage = storage,
                codexHome = defaults.codexHome,
                fileSystem = fileSystem,
                settingsDirectory = settingsDirectory,
                workingDirectory = workingDirectory,
                defaults = defaults,
                initialSettings = defaults,
            ).also { store -> store.reload() }
        }
    }
}

/**
 * Opens effective global settings using Codex configuration rooted at this
 * [CodexCliStorage] and sparse private settings under [settingsDirectory].
 *
 * [workingDirectory] selects the project-level `.codex` Hook source.
 */
public suspend fun CodexCliStorage.openGlobalSettings(
    settingsDirectory: Path,
    workingDirectory: Path,
    defaults: KodexGlobalSettings,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): KodexSettingsStore =
    KodexSettingsStore.open(
        storage = this,
        settingsDirectory = settingsDirectory,
        workingDirectory = workingDirectory,
        defaults = defaults,
        fileSystem = fileSystem,
    )

private suspend fun loadHookConfiguration(
    userStorage: CodexCliStorage,
    workingDirectory: Path,
    fileSystem: CoroutineFileSystem,
): HookConfiguration = HookConfiguration(
    sources = userStorage.readHookLayers(
        sourceKind = CodexCliHookSourceKind.User,
    ) + CodexCliStorage(
        directory = Path(workingDirectory, ".codex"),
        fileSystem = fileSystem,
    ).readHookLayers(
        sourceKind = CodexCliHookSourceKind.Project,
    ),
)

private class SettingsResolver(
    initial: KodexGlobalSettings,
) {
    private var settings = initial

    fun apply(config: CodexCliConfig) {
        config.model?.let { model ->
            settings = settings.copy(newSession = settings.newSession.copy(model = OpenAiModelId(model)))
        }
        config.reasoningEffort?.let { effort ->
            settings = settings.copy(
                newSession = settings.newSession.copy(reasoningEffort = effort),
            )
        }
        config.serviceTier?.toServiceTierOrNull()?.let { tier ->
            settings = settings.copy(newSession = settings.newSession.copy(serviceTier = tier))
        }
        applyNewLineKey(config)
        applyMcpServers(config)
    }

    fun resolve(): KodexGlobalSettings = settings

    private fun applyNewLineKey(config: CodexCliConfig) {
        val current = settings.newLineKey
        val submit = config.tui?.keymap?.composer?.submit
            ?: config.tui?.keymap?.global?.submit
            ?: current.submitWireValue
        val newLine = config.tui?.keymap?.editor?.insertNewline
            ?: current.newLineWireValue
        val value = when (submit to newLine) {
            "enter" to "shift-enter" -> NewLineKey.ShiftEnter
            "ctrl-enter" to "enter" -> NewLineKey.Enter
            else -> return
        }
        settings = settings.copy(newLineKey = value)
    }

    private fun applyMcpServers(config: CodexCliConfig) {
        if (config.mcpServers.isEmpty()) return
        settings = settings.copy(
            mcpServers = settings.mcpServers + config.mcpServers.mapValues { (_, server) ->
                server.toSettings()
            },
        )
    }
}

private fun CodexCliMcpServer.toSettings(): McpServerConfiguration =
    when (this) {
        is CodexCliMcpServer.StreamableHttp -> McpServerConfiguration.StreamableHttp(
            url = url,
            headers = headers,
            enabled = enabled,
        )

        is CodexCliMcpServer.Stdio -> McpServerConfiguration.Stdio(
            command = command,
            args = args,
            environment = env,
            workingDirectory = Path(cwd),
            enabled = enabled,
        )
    }

private fun KodexGlobalSettings.withOverride(
    override: GlobalSettingsFile,
): KodexGlobalSettings {
    var effective = this
    override.authSource?.let { value ->
        effective = effective.copy(authSource = value)
    }
    override.shell?.let { value ->
        effective = effective.copy(shell = value)
    }
    override.newLineKey?.let { value ->
        effective = effective.copy(newLineKey = value)
    }
    override.newSession?.let { values ->
        effective = effective.copy(
            newSession = effective.newSession.copy(
                model = values.model?.let(::OpenAiModelId) ?: effective.newSession.model,
                reasoningEffort = values.reasoningEffort ?: effective.newSession.reasoningEffort,
                serviceTier = values.serviceTier?.toServiceTier()
                    ?: effective.newSession.serviceTier,
                mode = values.mode ?: effective.newSession.mode,
            ),
        )
    }
    override.sessionTitle?.let { values ->
        effective = effective.copy(
            sessionTitle = SessionTitleSettings(
                enabled = values.enabled ?: effective.sessionTitle.enabled,
                model = values.model?.let(::OpenAiModelId) ?: effective.sessionTitle.model,
                reasoningEffort = values.reasoningEffort ?: effective.sessionTitle.reasoningEffort,
            ),
        )
    }
    override.mcpServers?.let { servers ->
        effective = effective.copy(
            mcpServers = servers,
        )
    }
    override.hooks?.let { hooks ->
        effective = effective.copy(hooks = hooks)
    }
    return effective
}

/**
 * Versioned sparse contents of Kodex's private `settings.yml`.
 *
 * @property shell Nullable because omission selects the host's default shell;
 * `null` means no Kodex override.
 * @property newLineKey Nullable because an omitted value inherits Codex/default
 * behavior; `null` means no Kodex override.
 * @property newSession Nullable because all new-session fields may inherit;
 * `null` means this section contributes no override.
 * @property sessionTitle Nullable because automatic-title controls may use
 * compiled defaults; `null` means this section contributes no override.
 * @property mcpServers Nullable because omission inherits native Codex MCP
 * servers; an empty map explicitly disables all inherited servers.
 * @property hooks Nullable because omission inherits Codex Home and project
 * Hook sources; a non-null value is a complete Kodex-owned replacement,
 * including an empty configuration.
 */
@Serializable
private data class GlobalSettingsFile(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("schema_version")
    val schemaVersion: Int = CurrentGlobalSettingsSchemaVersion,
    @SerialName("codex_home")
    val codexHome: String? = null,
    @SerialName("auth_source")
    val authSource: KodexAuthSource? = null,
    val shell: Shell? = null,
    @SerialName("new_line_key")
    val newLineKey: NewLineKey? = null,
    @SerialName("new_session")
    val newSession: NewSessionOverride? = null,
    @SerialName("session_title")
    val sessionTitle: SessionTitleOverride? = null,
    @SerialName("mcp_servers")
    val mcpServers: Map<String, McpServerConfiguration>? = null,
    val hooks: HookConfiguration? = null,
)

private fun GlobalSettingsFile.withChanges(
    current: KodexGlobalSettings,
    updated: KodexGlobalSettings,
): GlobalSettingsFile = copy(
    codexHome = updated.codexHome.toString(),
    authSource = updated.authSource.takeIf { it != current.authSource } ?: authSource,
    shell = updated.shell.takeIf { it != current.shell } ?: shell,
    newLineKey = updated.newLineKey.takeIf { it != current.newLineKey } ?: newLineKey,
    newSession = newSession.withChanges(current.newSession, updated.newSession),
    sessionTitle = sessionTitle.withChanges(current.sessionTitle, updated.sessionTitle),
    mcpServers = if (updated.mcpServers != current.mcpServers) {
        updated.mcpServers
    } else {
        mcpServers
    },
    hooks = if (updated.hooks != current.hooks) {
        updated.hooks
    } else {
        hooks
    },
)

private fun NewSessionOverride?.withChanges(
    current: KodexNewSessionSettings,
    updated: KodexNewSessionSettings,
): NewSessionOverride? {
    val existing = this ?: NewSessionOverride()
    return existing.copy(
        model = updated.model.value.takeIf { updated.model != current.model } ?: existing.model,
        reasoningEffort = updated.reasoningEffort
            .takeIf { updated.reasoningEffort != current.reasoningEffort } ?: existing.reasoningEffort,
        serviceTier = updated.serviceTier.requestValue
            .takeIf { updated.serviceTier != current.serviceTier } ?: existing.serviceTier,
        mode = updated.mode.takeIf { updated.mode != current.mode } ?: existing.mode,
    ).takeUnless(NewSessionOverride::isEmpty)
}

private fun SessionTitleOverride?.withChanges(
    current: SessionTitleSettings,
    updated: SessionTitleSettings,
): SessionTitleOverride? {
    val existing = this ?: SessionTitleOverride()
    return existing.copy(
        enabled = updated.enabled.takeIf { updated.enabled != current.enabled } ?: existing.enabled,
        model = if (updated.model != current.model) updated.model?.value else existing.model,
        reasoningEffort = updated.reasoningEffort
            .takeIf { updated.reasoningEffort != current.reasoningEffort } ?: existing.reasoningEffort,
    ).takeUnless(SessionTitleOverride::isEmpty)
}

/**
 * Sparse override for settings copied into new sessions.
 *
 * Every nullable property means that field inherits its value from the Codex
 * config layers selected by private `settings.yml`.
 */
@Serializable
private data class NewSessionOverride(
    val model: String? = null,
    @SerialName("reasoning_effort")
    val reasoningEffort: ReasoningEffort? = null,
    @SerialName("service_tier")
    val serviceTier: String? = null,
    val mode: ModeKind? = null,
) {
    fun isEmpty(): Boolean =
        model == null && reasoningEffort == null && serviceTier == null && mode == null
}

/**
 * Sparse overrides for automatic title generation.
 *
 * @property enabled Nullable because omission inherits the lower-precedence
 * setting; `null` contributes no enabled override.
 * @property model Nullable because omission inherits the lower-precedence
 * setting; `null` contributes no model override.
 * @property reasoningEffort Nullable because omission inherits the
 * lower-precedence setting; `null` contributes no reasoning-effort override.
 */
@Serializable
private data class SessionTitleOverride(
    val enabled: Boolean? = null,
    val model: String? = null,
    @SerialName("reasoning_effort")
    val reasoningEffort: ReasoningEffort? = null,
) {
    fun isEmpty(): Boolean = enabled == null && model == null && reasoningEffort == null
}

/**
 * @return Nullable because Codex may add service tiers before Kodex models
 * them; `null` means the unknown native value does not override lower layers.
 */
private fun String.toServiceTierOrNull(): ServiceTier? = when (this) {
    "default" -> ServiceTier.Default
    "fast", "priority" -> ServiceTier.Fast
    "flex" -> ServiceTier.Flex
    else -> null
}

private fun String.toServiceTier(): ServiceTier =
    toServiceTierOrNull() ?: throw IllegalArgumentException("Unsupported service tier '$this' in GlobalSettings.yml.")

private val NewLineKey.submitWireValue: String
    get() = when (this) {
        NewLineKey.ShiftEnter -> "enter"
        NewLineKey.Enter -> "ctrl-enter"
    }

private val NewLineKey.newLineWireValue: String
    get() = when (this) {
        NewLineKey.ShiftEnter -> "shift-enter"
        NewLineKey.Enter -> "enter"
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

private const val CurrentGlobalSettingsSchemaVersion: Int = 2
private const val KodexSettingsFileName: String = "settings.yml"
