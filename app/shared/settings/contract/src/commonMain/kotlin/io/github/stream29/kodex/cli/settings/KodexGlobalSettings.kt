package io.github.stream29.kodex.cli.settings

import io.github.stream29.kodex.hook.contract.HookConfiguration
import io.github.stream29.kodex.hook.contract.HookSettings
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.io.files.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Application-wide settings that apply independently of any one agent session.
 *
 * @property codexHome External Codex data source used only for selected
 * authentication and explicit MCP imports.
 * @property authSource Persistent source of subscription credentials.
 * @property shell Default shell advertised to Agents and used by shell tools.
 * @property newLineKey Key chord that a multiline text input treats as a newline.
 * @property newSession Defaults copied into each newly created thread.
 * @property sessionTitle Automatic session-title generation controls.
 * @property sidebars Application-wide content selected for each session sidebar.
 * @property mcpServers Application-wide MCP server configurations.
 * @property hooks Complete Kodex-owned Hook configuration.
 */
public data class KodexGlobalSettings(
    public val codexHome: Path,
    public val authSource: KodexAuthSource = KodexAuthSource.Codex,
    public override val shell: Shell = Shell.default,
    public val newLineKey: NewLineKey = NewLineKey.ShiftEnter,
    public val newSession: KodexNewSessionSettings = KodexNewSessionSettings(),
    public val sessionTitle: SessionTitleSettings = SessionTitleSettings(),
    public val sidebars: SidebarSettings = SidebarSettings(),
    public override val mcpServers: Map<String, McpServerConfiguration> = emptyMap(),
    public override val hooks: HookConfiguration = emptyMap(),
) : HookSettings, McpSettings, ShellSettings {
    init {
        require(hooks.keys.all(String::isNotBlank)) {
            "Hook names must not be blank."
        }
    }
}

/** Content selected for the independent left and right session sidebars. */
public data class SidebarSettings(
    public val left: SidebarContent = SidebarContent.TerminalSessions,
    public val right: SidebarContent = SidebarContent.None,
)

/** Content that a session sidebar can display. */
@Serializable
public enum class SidebarContent {
    /** Displays an empty sidebar body. */
    @SerialName("none")
    None,

    /** Displays terminal sessions owned by the selected agent. */
    @SerialName("terminal_sessions")
    TerminalSessions,
}

/** Selects whether subscription credentials come from Codex or Kodex storage. */
@Serializable
public enum class KodexAuthSource {
    /** Reads the selected Codex Home auth.json without modifying it. */
    @SerialName("codex")
    Codex,

    /** Reads and refreshes Kodex private auth.yml. */
    @SerialName("kodex")
    Kodex,
}

/** Defaults used to construct a new thread's first settings snapshot. */
public data class KodexNewSessionSettings(
    public val model: OpenAiModelId = OpenAiModelId("gpt-5.6-sol"),
    public val reasoningEffort: ReasoningEffort = ReasoningEffort.Medium,
    public val serviceTier: ServiceTier = ServiceTier.Default,
    public val requestUserInputMode: RequestUserInputMode = RequestUserInputMode.AskUser,
)

/**
 * Controls the one-shot title request for a newly materialized root session.
 *
 * @property enabled Whether the first accepted text may start title generation.
 * @property model Nullable because callers may use the title generator's
 * compiled default; `null` selects that default model.
 * @property reasoningEffort Reasoning effort sent with the title request.
 */
public data class SessionTitleSettings(
    public val enabled: Boolean = true,
    public val model: OpenAiModelId? = null,
    public val reasoningEffort: ReasoningEffort = ReasoningEffort.Low,
)

/** Key chord for inserting a newline, paired with the only non-conflicting [submitKey]. */
@Serializable
public enum class NewLineKey(
    public val submitKey: SubmitKey,
) {
    @SerialName("shift_enter")
    ShiftEnter(SubmitKey.Enter),

    @SerialName("enter")
    Enter(SubmitKey.CtrlEnter),
}

/** Key chord for submitting input, paired with the corresponding [newLineKey]. */
@Serializable
public enum class SubmitKey {
    Enter,
    CtrlEnter,
    ;

    public val newLineKey: NewLineKey
        get() = when (this) {
            Enter -> NewLineKey.ShiftEnter
            CtrlEnter -> NewLineKey.Enter
        }
}

/** Provides the latest application-wide settings snapshot. */
public interface KodexGlobalSettingsStore {
    /** Latest complete settings snapshot. */
    public val settings: StateFlow<KodexGlobalSettings>

    /** Reloads Kodex's private settings and publishes the resulting complete snapshot. */
    public suspend fun reload(): KodexGlobalSettings

    /** Atomically transforms and publishes the current settings snapshot. */
    public suspend fun update(
        transform: (KodexGlobalSettings) -> KodexGlobalSettings,
    ): KodexGlobalSettings
}

/** Holds [KodexGlobalSettings] in memory for the lifetime of the application. */
public class InMemoryKodexGlobalSettings(
    initialSettings: KodexGlobalSettings,
) : KodexGlobalSettingsStore {
    /** Latest complete settings snapshot. */
    override val settings: StateFlow<KodexGlobalSettings>
        field = MutableStateFlow(initialSettings)

    /** Returns the current snapshot because this implementation has no external settings source. */
    override suspend fun reload(): KodexGlobalSettings = settings.value

    /** Atomically transforms and publishes the current settings snapshot. */
    override suspend fun update(
        transform: (KodexGlobalSettings) -> KodexGlobalSettings,
    ): KodexGlobalSettings = settings.updateAndGet(transform)
}
