package io.github.stream29.kodex.cli.settings

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import io.github.stream29.kodex.hook.contract.DefaultHookTimeoutSeconds
import io.github.stream29.kodex.hook.contract.HookCodexImportIdentity
import io.github.stream29.kodex.hook.contract.HookCodexSourceKind
import io.github.stream29.kodex.hook.contract.HookCommandDefinition
import io.github.stream29.kodex.hook.contract.HookConfiguration
import io.github.stream29.kodex.hook.contract.HookDeclarations
import io.github.stream29.kodex.hook.contract.HookEnvironmentValue
import io.github.stream29.kodex.hook.contract.HookMatcher
import io.github.stream29.kodex.hook.contract.HookMatcherGroup
import io.github.stream29.kodex.hook.contract.HookSourceConfiguration
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.openai.AgentMode
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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
        val current = readFileOrNull()?.toSettings() ?: defaults
        val updated = transform(current)
        writeFile(GlobalSettingsFile.from(updated))
        updated.also { settings.value = it }
    }

    /** Reloads only Kodex's private settings snapshot. */
    override suspend fun reload(): KodexGlobalSettings = updateMutex.withLock {
        val stored = readFileOrNull()
        val resolved = stored?.toSettings() ?: defaults
        settings.value = resolved
        resolved
    }

    private suspend fun readFileOrNull(): GlobalSettingsFile? {
        if (!fileSystem.exists(settingsPath)) return null
        val text = fileSystem.readString(settingsPath)
        val schemaVersion = try {
            SettingsYaml.decodeFromString(SettingsVersionFile.serializer(), text).schemaVersion
        } catch (error: Exception) {
            throw invalidSettings(error)
        }
        return try {
            when (schemaVersion) {
                CurrentGlobalSettingsSchemaVersion ->
                    SettingsYaml.decodeFromString(GlobalSettingsFile.serializer(), text)

                PreviousGlobalSettingsSchemaVersion ->
                    migrateSchemaFour(text)

                LegacyGlobalSettingsSchemaVersion -> {
                    val legacy = SettingsYaml.decodeFromString(
                        LegacyGlobalSettingsFile.serializer(),
                        text,
                    )
                    GlobalSettingsFile.from(legacy.applyTo(defaults, fileSystem))
                        .also { migrated ->
                            writeFile(migrated)
                        }
                }

                else -> throw IllegalArgumentException(
                    "Unsupported Kodex global settings schema $schemaVersion at $settingsPath.",
                )
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw invalidSettings(error)
        }
    }

    private fun invalidSettings(cause: Exception): IllegalArgumentException =
        IllegalArgumentException(
            "Kodex global settings must be valid YAML at $settingsPath.",
            cause,
        )

    private suspend fun migrateSchemaFour(text: String): GlobalSettingsFile {
        val settings = try {
            SettingsYaml.decodeFromString(GlobalSettingsFile.serializer(), text).toSettings()
        } catch (currentShapeError: Exception) {
            try {
                SettingsYaml.decodeFromString(
                    LegacySchemaFourGlobalSettingsFile.serializer(),
                    text,
                ).toSettings(fileSystem)
            } catch (legacyShapeError: Exception) {
                legacyShapeError.addSuppressed(currentShapeError)
                throw legacyShapeError
            }
        }
        return GlobalSettingsFile.from(settings).also { migrated ->
            writeFile(migrated)
        }
    }

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

/** Version header decoded before selecting the compatible file model. */
@Serializable
private data class SettingsVersionFile(
    @SerialName("schema_version")
    val schemaVersion: Int,
)

/** Version 5 stores a complete Kodex-owned settings snapshot. */
@Serializable
private data class GlobalSettingsFile(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("schema_version")
    val schemaVersion: Int = CurrentGlobalSettingsSchemaVersion,
    @SerialName("codex_home")
    val codexHome: String,
    @SerialName("auth_source")
    val authSource: KodexAuthSource,
    val shell: Shell,
    @SerialName("new_line_key")
    val newLineKey: NewLineKey,
    @SerialName("new_session")
    val newSession: NewSessionFile,
    @SerialName("session_title")
    val sessionTitle: SessionTitleFile,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("mcp_servers")
    val mcpServers: Map<String, McpServerConfiguration> = emptyMap(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val hooks: HookConfiguration = HookConfiguration(),
) {
    fun toSettings(): KodexGlobalSettings =
        KodexGlobalSettings(
            codexHome = Path(codexHome),
            authSource = authSource,
            shell = shell,
            newLineKey = newLineKey,
            newSession = newSession.toSettings(),
            sessionTitle = sessionTitle.toSettings(),
            mcpServers = mcpServers,
            hooks = hooks,
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

/** Schema 4 complete snapshot accepted once and atomically migrated to schema 5. */
@Serializable
private data class LegacySchemaFourGlobalSettingsFile(
    @SerialName("schema_version")
    val schemaVersion: Int = PreviousGlobalSettingsSchemaVersion,
    @SerialName("codex_home")
    val codexHome: String,
    @SerialName("auth_source")
    val authSource: KodexAuthSource,
    val shell: Shell,
    @SerialName("new_line_key")
    val newLineKey: NewLineKey,
    @SerialName("new_session")
    val newSession: NewSessionFile,
    @SerialName("session_title")
    val sessionTitle: SessionTitleFile,
    @SerialName("mcp_servers")
    val mcpServers: Map<String, McpServerConfiguration> = emptyMap(),
    val hooks: LegacyHookConfiguration = LegacyHookConfiguration(),
) {
    suspend fun toSettings(fileSystem: CoroutineFileSystem): KodexGlobalSettings =
        KodexGlobalSettings(
            codexHome = Path(codexHome),
            authSource = authSource,
            shell = shell,
            newLineKey = newLineKey,
            newSession = newSession.toSettings(),
            sessionTitle = sessionTitle.toSettings(),
            mcpServers = mcpServers,
            hooks = hooks.toSettings(fileSystem),
        )
}

/** Schema 3 sparse file accepted once and atomically migrated to schema 5. */
@Serializable
private data class LegacyGlobalSettingsFile(
    @SerialName("schema_version")
    val schemaVersion: Int = LegacyGlobalSettingsSchemaVersion,
    @SerialName("codex_home")
    val codexHome: String? = null,
    @SerialName("auth_source")
    val authSource: KodexAuthSource? = null,
    val shell: Shell? = null,
    @SerialName("new_line_key")
    val newLineKey: NewLineKey? = null,
    @SerialName("new_session")
    val newSession: LegacyNewSessionFile? = null,
    @SerialName("session_title")
    val sessionTitle: LegacySessionTitleFile? = null,
    @SerialName("mcp_servers")
    val mcpServers: Map<String, McpServerConfiguration>? = null,
    val hooks: LegacyHookConfiguration? = null,
) {
    suspend fun applyTo(
        defaults: KodexGlobalSettings,
        fileSystem: CoroutineFileSystem,
    ): KodexGlobalSettings =
        defaults.copy(
            codexHome = codexHome?.let(::Path) ?: defaults.codexHome,
            authSource = authSource ?: defaults.authSource,
            shell = shell ?: defaults.shell,
            newLineKey = newLineKey ?: defaults.newLineKey,
            newSession = newSession?.applyTo(defaults.newSession) ?: defaults.newSession,
            sessionTitle = sessionTitle?.applyTo(defaults.sessionTitle) ?: defaults.sessionTitle,
            mcpServers = mcpServers ?: emptyMap(),
            hooks = hooks?.toSettings(fileSystem) ?: HookConfiguration(),
        )
}

/** Hook shape persisted before Kodex-owned source ids and import provenance. */
@Serializable
private data class LegacyHookConfiguration(
    val featureEnabled: Boolean = true,
    val sources: List<LegacyHookLayer> = emptyList(),
) {
    suspend fun toSettings(fileSystem: CoroutineFileSystem): HookConfiguration =
        HookConfiguration(
            featureEnabled = featureEnabled,
            sources = sources.mapIndexed { index, source ->
                source.toSettings(
                    id = "migrated-hook-source-${index + 1}",
                    fileSystem = fileSystem,
                )
            },
        )
}

/** Private wire shape retained only to migrate pre-independent Hook settings. */
@Serializable
private data class LegacyHookLayer(
    val sourcePath: String,
    val sourceKind: LegacyHookSourceKind,
    val environment: Map<String, String> = emptyMap(),
    val description: String? = null,
    val hooks: LegacyHookDeclarations = LegacyHookDeclarations(),
)

@Serializable
private enum class LegacyHookSourceKind {
    @SerialName("system")
    System,

    @SerialName("user")
    User,

    @SerialName("project")
    Project,

    @SerialName("session")
    Session,
}

@Serializable
private data class LegacyHookDeclarations(
    @SerialName("PreToolUse")
    val preToolUse: List<LegacyHookMatcherGroup> = emptyList(),
    @SerialName("PermissionRequest")
    val permissionRequest: List<LegacyHookMatcherGroup> = emptyList(),
    @SerialName("PostToolUse")
    val postToolUse: List<LegacyHookMatcherGroup> = emptyList(),
    @SerialName("PreCompact")
    val preCompact: List<LegacyHookMatcherGroup> = emptyList(),
    @SerialName("PostCompact")
    val postCompact: List<LegacyHookMatcherGroup> = emptyList(),
    @SerialName("UserPromptSubmit")
    val userPromptSubmit: List<LegacyHookMatcherGroup> = emptyList(),
    @SerialName("SubagentStart")
    val subagentStart: List<LegacyHookMatcherGroup> = emptyList(),
    @SerialName("SubagentStop")
    val subagentStop: List<LegacyHookMatcherGroup> = emptyList(),
    @SerialName("Stop")
    val stop: List<LegacyHookMatcherGroup> = emptyList(),
)

@Serializable
private data class LegacyHookMatcherGroup(
    val matcher: LegacyHookMatcher = LegacyHookMatcher("*"),
    val hooks: List<LegacyHookHandler> = emptyList(),
)

@Serializable(with = LegacyHookMatcherSerializer::class)
private data class LegacyHookMatcher(
    val pattern: String,
)

private object LegacyHookMatcherSerializer : KSerializer<LegacyHookMatcher> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LegacyHookMatcher", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LegacyHookMatcher) {
        encoder.encodeString(value.pattern)
    }

    override fun deserialize(decoder: Decoder): LegacyHookMatcher =
        LegacyHookMatcher(decoder.decodeString())
}

@Serializable
private sealed interface LegacyHookHandler {
    @Serializable
    @SerialName("command")
    data class Command(
        val command: String,
        @SerialName("commandWindows")
        private val commandWindows: String? = null,
        @SerialName("command_windows")
        private val commandWindowsAlias: String? = null,
        @SerialName("timeout")
        val timeoutSeconds: Long? = null,
        val async: Boolean = false,
        @SerialName("statusMessage")
        val statusMessage: String? = null,
        @SerialName("additionalContextLimit")
        val additionalContextLimit: Int? = null,
    ) : LegacyHookHandler {
        @Transient
        val platformCommand: String =
            if (Shell.default.type == ShellType.PowerShell || Shell.default.type == ShellType.Cmd) {
                commandWindows ?: commandWindowsAlias ?: command
            } else {
                command
            }
    }

    @Serializable
    @SerialName("prompt")
    data object Prompt : LegacyHookHandler

    @Serializable
    @SerialName("agent")
    data object Agent : LegacyHookHandler
}

private suspend fun LegacyHookLayer.toSettings(
    id: String,
    fileSystem: CoroutineFileSystem,
): HookSourceConfiguration {
    val path = Path(sourcePath)
    val normalizedPath = try {
        fileSystem.resolve(path).toString()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        sourcePath
    }
    return HookSourceConfiguration(
        id = id,
        name = description
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: "${sourceKind.name.lowercase()} ${path.name}",
        importIdentity = sourceKind.toImportSourceKind()?.let { kind ->
            HookCodexImportIdentity(
                sourceKind = kind,
                normalizedPath = normalizedPath,
            )
        },
        environment = environment.mapValues { (_, value) ->
            HookEnvironmentValue(value)
        },
        hooks = hooks.toSettings(environment),
    )
}

private fun LegacyHookSourceKind.toImportSourceKind(): HookCodexSourceKind? =
    when (this) {
        LegacyHookSourceKind.User -> HookCodexSourceKind.User
        LegacyHookSourceKind.Project -> HookCodexSourceKind.Project
        LegacyHookSourceKind.System,
        LegacyHookSourceKind.Session,
            -> null
    }

private fun LegacyHookDeclarations.toSettings(
    environment: Map<String, String>,
): HookDeclarations =
    HookDeclarations(
        preToolUse = preToolUse.toSettings(environment),
        permissionRequest = permissionRequest.toSettings(environment),
        postToolUse = postToolUse.toSettings(environment),
        preCompact = preCompact.toSettings(environment),
        postCompact = postCompact.toSettings(environment),
        userPromptSubmit = userPromptSubmit.toSettings(environment),
        stop = stop.toSettings(environment),
    )

private fun List<LegacyHookMatcherGroup>.toSettings(
    environment: Map<String, String>,
): List<HookMatcherGroup> =
    mapNotNull { group ->
        val commands = group.hooks.mapNotNull { handler ->
            val command = handler as? LegacyHookHandler.Command
                ?: return@mapNotNull null
            val expanded = command.platformCommand.substituteEnvironment(environment)
            if (
                command.async ||
                expanded.isBlank() ||
                command.additionalContextLimit?.let { limit -> limit < 0 } == true
            ) {
                return@mapNotNull null
            }
            HookCommandDefinition(
                command = expanded,
                timeoutSeconds = (command.timeoutSeconds ?: DefaultHookTimeoutSeconds)
                    .coerceAtLeast(1L),
                statusMessage = command.statusMessage,
                additionalContextLimit = command.additionalContextLimit,
            )
        }
        commands.takeIf(List<*>::isNotEmpty)?.let {
            HookMatcherGroup(
                matcher = HookMatcher.parse(group.matcher.pattern),
                hooks = commands,
            )
        }
    }

private fun String.substituteEnvironment(environment: Map<String, String>): String =
    environment.entries.fold(this) { command, (name, value) ->
        command.replace("\${$name}", value)
    }

@Serializable
private data class NewSessionFile(
    val model: String,
    @SerialName("reasoning_effort")
    val reasoningEffort: ReasoningEffort,
    @SerialName("service_tier")
    val serviceTier: String,
    @SerialName("agent_mode")
    val agentMode: AgentMode,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("request_user_input_mode")
    val requestUserInputMode: RequestUserInputMode = RequestUserInputMode.AskUser,
) {
    fun toSettings(): KodexNewSessionSettings =
        KodexNewSessionSettings(
            model = OpenAiModelId(model),
            reasoningEffort = reasoningEffort,
            serviceTier = serviceTier.toServiceTier(),
            agentMode = agentMode,
            requestUserInputMode = requestUserInputMode,
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
private data class LegacyNewSessionFile(
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
}

@Serializable
private data class SessionTitleFile(
    val enabled: Boolean,
    val model: String? = null,
    @SerialName("reasoning_effort")
    val reasoningEffort: ReasoningEffort,
) {
    fun toSettings(): SessionTitleSettings =
        SessionTitleSettings(
            enabled = enabled,
            model = model?.let(::OpenAiModelId),
            reasoningEffort = reasoningEffort,
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

@Serializable
private data class LegacySessionTitleFile(
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

private const val CurrentGlobalSettingsSchemaVersion: Int = 5
private const val PreviousGlobalSettingsSchemaVersion: Int = 4
private const val LegacyGlobalSettingsSchemaVersion: Int = 3
private const val KodexSettingsFileName: String = "settings.yml"
