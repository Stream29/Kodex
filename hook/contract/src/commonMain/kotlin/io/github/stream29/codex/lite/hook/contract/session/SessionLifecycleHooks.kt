package io.github.stream29.codex.lite.hook.contract.session

/** Hook port owned by the session host. */
public interface SessionLifecycleHooks {
    /**
     * Observes Session startup.
     *
     * Session startup is a lifecycle fact. Hook output cannot block it or
     * contribute model context.
     */
    public suspend fun onSessionStart(request: SessionStartRequest)

    public suspend fun onSessionEnd(request: SessionEndRequest)
}

/** Session lifecycle implementation with no external behavior. */
public data object NoOpSessionLifecycleHooks : SessionLifecycleHooks {
    override suspend fun onSessionStart(request: SessionStartRequest): Unit = Unit

    override suspend fun onSessionEnd(request: SessionEndRequest): Unit = Unit
}
