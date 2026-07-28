package io.github.stream29.codex.lite.agentruntime.sessionhook

import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentstorage.contract.latestValue
import io.github.stream29.codex.lite.hook.contract.toHookSessionContext
import io.github.stream29.codex.lite.hook.contract.session.SessionEndRequest
import io.github.stream29.codex.lite.hook.contract.session.SessionLifecycleHooks
import io.github.stream29.codex.lite.hook.contract.session.SessionStartRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Installs root Session lifecycle Hooks on this runtime's inherited State scope.
 *
 * SessionStart is an observation-only notification and completes before this
 * function returns. SessionEnd runs once when the AgentState scope ends,
 * before its owning AgentSession releases the storage. The fixed external
 * lifecycle is `resume` on installation and `close` on scope completion.
 */
public suspend fun CodexAgentRuntime.installSessionHooks(
    hooks: SessionLifecycleHooks,
) {
    val settings = storage.settings.latestValue()
    hooks.onSessionStart(
        SessionStartRequest(
            context = settings.toHookSessionContext(storage.id),
        ),
    )

    launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    withTimeoutOrNull(10.seconds) {
                        val latestSettings = storage.settings.latestValue()
                        hooks.onSessionEnd(
                            SessionEndRequest(
                                context = latestSettings.toHookSessionContext(storage.id),
                            ),
                        )
                    }
                }
            }
        }
    }
}
