package io.github.stream29.codex.lite.hook.contract.session

/** Hook port owned by the session host. */
public interface SessionLifecycleHooks {
    public suspend fun onSessionStart(request: SessionStartRequest): SessionStartResult

    public suspend fun onSessionEnd(request: SessionEndRequest)
}

/** Session lifecycle implementation with no external behavior. */
public data object NoOpSessionLifecycleHooks : SessionLifecycleHooks {
    override suspend fun onSessionStart(request: SessionStartRequest): SessionStartResult =
        SessionStartResult.Continue()

    override suspend fun onSessionEnd(request: SessionEndRequest): Unit = Unit
}
