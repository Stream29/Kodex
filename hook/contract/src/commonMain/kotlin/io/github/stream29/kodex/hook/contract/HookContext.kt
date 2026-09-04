package io.github.stream29.kodex.hook.contract

import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlinx.io.files.Path

/**
 * Session data projected from the state snapshot visible at one Hook boundary.
 *
 * @property uri URI of the backing Agent storage.
 * @property cwd Working directory active at the Hook boundary.
 * @property model Model active at the Hook boundary.
 */
public data class HookSessionContext(
    public val uri: String,
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
 * @param uri URI of the Agent storage that owns [this].
 */
public fun KodexAgentSettings.toHookSessionContext(uri: String): HookSessionContext =
    HookSessionContext(
        uri = uri,
        cwd = cwd,
        model = model.value,
    )

/**
 * Projects one persisted Agent settings snapshot into Hook turn data.
 *
 * @param uri URI of the Agent storage that owns [this].
 * @param turnId Persisted identity of the active logical user turn.
 */
public fun KodexAgentSettings.toHookTurnContext(
    uri: String,
    turnId: String,
): HookTurnContext =
    HookTurnContext(
        session = toHookSessionContext(uri),
        turnId = turnId,
    )
