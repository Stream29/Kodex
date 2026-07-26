package io.github.stream29.codex.lite.cli.settings

import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextSettings
import io.github.stream29.codex.lite.hook.contract.HookConfiguration
import io.github.stream29.codex.lite.hook.contract.HookSettings
import io.github.stream29.codex.lite.openai.ModeKind
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.ReasoningEffort
import io.github.stream29.codex.lite.openai.ServiceTier
import io.github.stream29.codex.lite.utils.shellclient.Shell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.io.files.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Application-wide settings that apply independently of any one agent session.
 *
 * @property codexHome Root directory of the local Codex CLI data.
 * @property shell Default shell advertised to Agents and used by shell tools.
 * @property newLineKey Key chord that a multiline text input treats as a newline.
 * @property newSession Defaults copied into each newly created thread.
 * @property sessionTitle Automatic session-title generation controls.
 * @property mcpServers Application-wide Streamable HTTP MCP server configurations.
 * @property hooks Effective Hook configuration. It inherits the
 * selected Codex Home and project configuration until Codex Lite persists a
 * complete override.
 */
public data class CodexGlobalSettings(
    public override val codexHome: Path,
    public override val shell: Shell = Shell.default,
    public val newLineKey: NewLineKey = NewLineKey.ShiftEnter,
    public val newSession: CodexNewSessionSettings = CodexNewSessionSettings(),
    public val sessionTitle: SessionTitleSettings = SessionTitleSettings(),
    public val mcpServers: Map<String, McpServerSettings> = emptyMap(),
    public override val hooks: HookConfiguration = HookConfiguration(),
) : AgentContextSettings, HookSettings

/** Defaults used to construct a new thread's first settings snapshot. */
public data class CodexNewSessionSettings(
    public val model: OpenAiModelId = OpenAiModelId("gpt-5.6-sol"),
    public val reasoningEffort: ReasoningEffort = ReasoningEffort.Medium,
    public val serviceTier: ServiceTier = ServiceTier.Default,
    public val mode: ModeKind = ModeKind.Default,
)

/**
 * Controls the one-shot title request for a newly materialized root session.
 *
 * @property enabled Whether the first accepted text may start title generation.
 * @property model Nullable because callers may use the title generator's
 * compiled default; `null` selects that default model.
 */
public data class SessionTitleSettings(
    public val enabled: Boolean = true,
    public val model: OpenAiModelId? = null,
)

/** Global configuration for one Streamable HTTP MCP server. */
public data class McpServerSettings(
    public val url: String,
    public val headers: Map<String, String> = emptyMap(),
    public val enabled: Boolean = true,
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

/**
 * Holds [CodexGlobalSettings] in memory for the lifetime of the application.
 *
 * This intentionally performs no persistence. A durable settings backend can replace this class
 * later without changing the settings snapshot itself.
 */
public interface CodexGlobalSettingsStore {
    /** Latest complete settings snapshot. */
    public val settings: StateFlow<CodexGlobalSettings>

    /** Atomically transforms and publishes the current settings snapshot. */
    public suspend fun update(
        transform: (CodexGlobalSettings) -> CodexGlobalSettings,
    ): CodexGlobalSettings
}

public class InMemoryCodexGlobalSettings(
    initialSettings: CodexGlobalSettings,
) : CodexGlobalSettingsStore {
    /** Latest complete settings snapshot. */
    override val settings: StateFlow<CodexGlobalSettings>
        field = MutableStateFlow(initialSettings)

    /** Atomically transforms and publishes the current settings snapshot. */
    override suspend fun update(
        transform: (CodexGlobalSettings) -> CodexGlobalSettings,
    ): CodexGlobalSettings = settings.updateAndGet(transform)
}
