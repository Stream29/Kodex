package io.github.stream29.kodex.hook.contract

import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlinx.io.files.Path

/**
 * Session data projected from the state snapshot visible at one Hook boundary.
 *
 * @property sessionId Identity of the backing Agent storage.
 * @property cwd Working directory active at the Hook boundary.
 * @property model Model active at the Hook boundary.
 */
public data class HookSessionContext(
    public val sessionId: String,
    public val cwd: Path,
    public val model: String,
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
    )

/**
 * Projects one persisted Agent settings snapshot into Hook turn data.
 *
 * @param sessionId Identity of the Agent storage that owns [this].
 * @param turnId Persisted identity of the active logical user turn.
 */
public fun KodexAgentSettings.toHookTurnContext(
    sessionId: String,
    turnId: String,
): HookTurnContext =
    HookTurnContext(
        session = toHookSessionContext(sessionId),
        turnId = turnId,
    )
