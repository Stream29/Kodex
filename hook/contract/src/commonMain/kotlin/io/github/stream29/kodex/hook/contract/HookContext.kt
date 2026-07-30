package io.github.stream29.kodex.hook.contract

import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModeKind
import kotlinx.io.files.Path

/** Permission mode exposed to command hooks. */
public enum class HookPermissionMode(public val wireName: String) {
    Default("default"),
    AcceptEdits("acceptEdits"),
    Plan("plan"),
    DontAsk("dontAsk"),
    BypassPermissions("bypassPermissions"),
}

/**
 * Session data projected from the state snapshot visible at one Hook boundary.
 *
 * @property sessionId Identity of the backing Agent storage.
 * @property cwd Working directory active at the Hook boundary.
 * @property model Model active at the Hook boundary.
 * @property permissionMode Effective permission behavior exposed to Hooks.
 */
public data class HookSessionContext(
    public val sessionId: String,
    public val cwd: Path,
    public val model: String,
    public val permissionMode: HookPermissionMode,
)

/**
 * Hook context for one persisted Agent turn.
 *
 * @property session Session data visible at the Hook boundary.
 * @property turnId Persisted identity of the active logical user turn.
 */
public data class HookTurnContext(
    public val session: HookSessionContext,
    public val turnId: String,
)

/**
 * Projects one persisted Agent settings snapshot into Hook session data.
 *
 * @param sessionId Identity of the Agent storage that owns [this].
 */
public fun KodexAgentSettings.toHookSessionContext(sessionId: String): HookSessionContext =
    HookSessionContext(
        sessionId = sessionId,
        cwd = cwd,
        model = model.value,
        permissionMode = when (collaborationMode) {
            ModeKind.Default -> HookPermissionMode.BypassPermissions
            ModeKind.Plan -> HookPermissionMode.Plan
        },
    )

/**
 * Projects one persisted Agent settings snapshot into Hook turn data.
 *
 * @param sessionId Identity of the Agent storage that owns [this].
 */
public fun KodexAgentSettings.toHookTurnContext(sessionId: String): HookTurnContext =
    HookTurnContext(
        session = toHookSessionContext(sessionId),
        turnId = turnId,
    )
