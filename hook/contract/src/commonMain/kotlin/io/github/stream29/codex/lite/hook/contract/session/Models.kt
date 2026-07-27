package io.github.stream29.codex.lite.hook.contract.session

import io.github.stream29.codex.lite.hook.contract.HookSessionContext

/** Logical session boundary exposed to SessionStart matcher selection. */
public enum class SessionStartSource(public val wireName: String) {
    Startup("startup"),
    Resume("resume"),
    Clear("clear"),
}

public data class SessionStartRequest(
    public val context: HookSessionContext,
    public val source: SessionStartSource,
)

/** Result of running the initial SessionStart lifecycle event. */
public sealed interface SessionStartResult {
    public val additionalContexts: List<String>

    public data class Continue(
        override val additionalContexts: List<String> = emptyList(),
    ) : SessionStartResult

    /**
     * @property reason Nullable because `continue:false` may omit a reason;
     * `null` means startup stopped without hook feedback.
     */
    public data class Stop(
        public val reason: String?,
        override val additionalContexts: List<String> = emptyList(),
    ) : SessionStartResult
}

/** Reason a host is releasing a live session identity. */
public enum class SessionEndReason(public val wireName: String) {
    Close("close"),
    Clear("clear"),
    Shutdown("shutdown"),
}

public data class SessionEndRequest(
    public val context: HookSessionContext,
    public val reason: SessionEndReason,
)
