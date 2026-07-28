package io.github.stream29.codex.lite.hook.contract.session

import io.github.stream29.codex.lite.hook.contract.HookSessionContext

public data class SessionStartRequest(
    public val context: HookSessionContext,
)

public data class SessionEndRequest(
    public val context: HookSessionContext,
)
