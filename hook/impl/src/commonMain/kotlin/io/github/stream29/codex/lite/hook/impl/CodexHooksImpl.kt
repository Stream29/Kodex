package io.github.stream29.codex.lite.hook.impl

import io.github.stream29.codex.lite.hook.contract.CodexHooks
import io.github.stream29.codex.lite.hook.contract.compaction.CompactionHookRequest
import io.github.stream29.codex.lite.hook.contract.compaction.CompactionHookResult
import io.github.stream29.codex.lite.hook.contract.tool.HookToolInvocation
import io.github.stream29.codex.lite.hook.contract.approval.PermissionRequest
import io.github.stream29.codex.lite.hook.contract.approval.PermissionRequestResult
import io.github.stream29.codex.lite.hook.contract.tool.PostToolUseRequest
import io.github.stream29.codex.lite.hook.contract.tool.PostToolUseResult
import io.github.stream29.codex.lite.hook.contract.tool.PreToolUseResult
import io.github.stream29.codex.lite.hook.contract.session.SessionEndRequest
import io.github.stream29.codex.lite.hook.contract.session.SessionStartRequest
import io.github.stream29.codex.lite.hook.contract.session.SessionStartResult
import io.github.stream29.codex.lite.hook.contract.turn.StopRequest
import io.github.stream29.codex.lite.hook.contract.turn.StopResult
import io.github.stream29.codex.lite.hook.contract.turn.UserPromptSubmitRequest
import io.github.stream29.codex.lite.hook.contract.turn.UserPromptSubmitResult
import io.github.stream29.codex.lite.hook.impl.projection.CompactCommandInputWire
import io.github.stream29.codex.lite.hook.impl.projection.HookJson
import io.github.stream29.codex.lite.hook.impl.projection.PermissionRequestCommandInputWire
import io.github.stream29.codex.lite.hook.impl.projection.PostToolUseCommandInputWire
import io.github.stream29.codex.lite.hook.impl.projection.PreToolUseCommandInputWire
import io.github.stream29.codex.lite.hook.impl.projection.SessionEndCommandInputWire
import io.github.stream29.codex.lite.hook.impl.projection.SessionStartCommandInputWire
import io.github.stream29.codex.lite.hook.impl.projection.StopCommandInputWire
import io.github.stream29.codex.lite.hook.impl.projection.UserPromptSubmitCommandInputWire
import io.github.stream29.codex.lite.hook.impl.projection.toCompactionResult
import io.github.stream29.codex.lite.hook.impl.projection.toPostCompactCommandInputWire
import io.github.stream29.codex.lite.hook.impl.projection.toPermissionRequestResult
import io.github.stream29.codex.lite.hook.impl.projection.toPostToolUseResult
import io.github.stream29.codex.lite.hook.impl.projection.toPreCompactCommandInputWire
import io.github.stream29.codex.lite.hook.impl.projection.toPreToolUseResult
import io.github.stream29.codex.lite.hook.impl.projection.toSessionStartResult
import io.github.stream29.codex.lite.hook.impl.projection.toStopResult
import io.github.stream29.codex.lite.hook.impl.projection.toUserPromptSubmitResult
import io.github.stream29.codex.lite.utils.shellclient.ShellClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.newCoroutineContext

/**
 * [CodexHooks] implementation using a resolved view of global settings.
 *
 * Each invocation reads one immutable resolved snapshot before selecting handlers.
 * Settings changes cannot alter that invocation after handler selection starts.
 */
public class CodexHooksImpl internal constructor(
    scope: CoroutineScope,
    settings: StateFlow<HookSettings>,
) :
    CodexHooks,
    CoroutineScope by scope {
    private val shellClient: ShellClient = this.ShellClient()
    internal val resolvedHooks: StateFlow<ResolvedHooks> = settings
        .map { settings -> settings.hooks }
        .distinctUntilChanged()
        .map { configuration -> configuration.resolveHooks() }
        .stateIn(
            scope = this,
            started = SharingStarted.Eagerly,
            initialValue = settings.value.hooks.resolveHooks(),
        )

    override suspend fun onSessionStart(request: SessionStartRequest): SessionStartResult {
        val hooks = currentHooks()
        val context = request.context
        val completed = shellClient.runHooks(
            hooks = hooks.sessionStart.matching(listOf(request.source.wireName)),
            inputJson = HookJson.encodeToString(
                SessionStartCommandInputWire(
                    sessionId = context.sessionId,
                    transcriptPath = null,
                    cwd = context.cwd.toString(),
                    model = context.model,
                    permissionMode = context.permissionMode.wireName,
                    source = request.source.wireName,
                ),
            ),
            cwd = context.cwd,
        ).map(HookRawResult::toSessionStartResult)
        val contexts = completed.flatMap { result -> result.additionalContexts }
        val stopped = completed.firstNotNullOfOrNull { result -> result as? SessionStartResult.Stop }
        return if (stopped == null) {
            SessionStartResult.Continue(contexts)
        } else {
            SessionStartResult.Stop(stopped.reason, contexts)
        }
    }

    override suspend fun onSessionEnd(request: SessionEndRequest) {
        val hooks = currentHooks()
        val context = request.context
        shellClient.runHooks(
            hooks = hooks.sessionEnd.matching(listOf(request.reason.wireName)),
            inputJson = HookJson.encodeToString(
                SessionEndCommandInputWire(
                    sessionId = context.sessionId,
                    transcriptPath = null,
                    cwd = context.cwd.toString(),
                    model = context.model,
                    permissionMode = context.permissionMode.wireName,
                    reason = request.reason.wireName,
                ),
            ),
            cwd = context.cwd,
        )
    }

    override suspend fun onUserPromptSubmit(
        request: UserPromptSubmitRequest,
    ): UserPromptSubmitResult {
        val hooks = currentHooks()
        val context = request.context
        val session = context.session
        val completed = shellClient.runHooks(
            hooks = hooks.userPromptSubmit,
            inputJson = HookJson.encodeToString(
                UserPromptSubmitCommandInputWire(
                    sessionId = session.sessionId,
                    turnId = context.turnId,
                    transcriptPath = null,
                    cwd = session.cwd.toString(),
                    model = session.model,
                    permissionMode = session.permissionMode.wireName,
                    prompt = request.prompt,
                ),
            ),
            cwd = session.cwd,
        ).map(HookRawResult::toUserPromptSubmitResult)
        val contexts = completed.flatMap { result -> result.additionalContexts }
        val stopped = completed.firstNotNullOfOrNull { result -> result as? UserPromptSubmitResult.Stop }
        return if (stopped == null) {
            UserPromptSubmitResult.Continue(contexts)
        } else {
            UserPromptSubmitResult.Stop(stopped.reason, contexts)
        }
    }

    override suspend fun onStop(request: StopRequest): StopResult {
        val hooks = currentHooks()
        val context = request.context
        val session = context.session
        val matchedHooks = hooks.stop
        val hookRunId = matchedHooks.joinToString(separator = "|") { hook ->
            hook.id
        }
        val completed = shellClient.runHooks(
            hooks = matchedHooks,
            inputJson = HookJson.encodeToString(
                StopCommandInputWire(
                    sessionId = session.sessionId,
                    turnId = context.turnId,
                    transcriptPath = null,
                    cwd = session.cwd.toString(),
                    model = session.model,
                    permissionMode = session.permissionMode.wireName,
                    stopHookActive = request.stopHookActive,
                    lastAssistantMessage = request.lastAssistantMessage,
                ),
            ),
            cwd = session.cwd,
        ).map { result -> result.toStopResult(hookRunId) }
        completed.firstNotNullOfOrNull { result -> result as? StopResult.Stop }?.let { stopped ->
            return stopped
        }
        val fragments = completed
            .filterIsInstance<StopResult.Continue>()
            .flatMap(StopResult.Continue::fragments)
        return if (fragments.isEmpty()) StopResult.Finish else StopResult.Continue(fragments)
    }

    override suspend fun onPreToolUse(invocation: HookToolInvocation): PreToolUseResult {
        val hooks = currentHooks()
        val context = invocation.context
        val session = context.session
        val matcherInputs = invocation.matcherInputs()
        var effectiveInput = invocation.input
        var wasUpdated = false
        val contexts = mutableListOf<String>()
        for (hook in hooks.preToolUse.matching(matcherInputs)) {
            val result = shellClient.runHook(
                hook = hook,
                inputJson = HookJson.encodeToString(
                    PreToolUseCommandInputWire(
                        sessionId = session.sessionId,
                        turnId = context.turnId,
                        transcriptPath = null,
                        cwd = session.cwd.toString(),
                        model = session.model,
                        permissionMode = session.permissionMode.wireName,
                        toolName = invocation.toolName,
                        toolInput = effectiveInput,
                        toolUseId = invocation.toolUseId,
                    ),
                ),
                cwd = session.cwd,
            ).toPreToolUseResult()
            contexts += result.additionalContexts
            when (result) {
                is PreToolUseResult.Block ->
                    return PreToolUseResult.Block(result.reason, contexts)

                is PreToolUseResult.Continue -> result.updatedInput?.let { updatedInput ->
                    effectiveInput = updatedInput
                    wasUpdated = true
                }
            }
        }
        return PreToolUseResult.Continue(
            updatedInput = if (wasUpdated) effectiveInput else null,
            additionalContexts = contexts,
        )
    }

    override suspend fun onPostToolUse(request: PostToolUseRequest): PostToolUseResult {
        val hooks = currentHooks()
        val invocation = request.invocation
        val context = invocation.context
        val session = context.session
        val completed = shellClient.runHooks(
            hooks = hooks.postToolUse.matching(invocation.matcherInputs()),
            inputJson = HookJson.encodeToString(
                PostToolUseCommandInputWire(
                    sessionId = session.sessionId,
                    turnId = context.turnId,
                    transcriptPath = null,
                    cwd = session.cwd.toString(),
                    model = session.model,
                    permissionMode = session.permissionMode.wireName,
                    toolName = invocation.toolName,
                    toolInput = invocation.input,
                    toolResponse = request.response,
                    toolUseId = invocation.toolUseId,
                ),
            ),
            cwd = session.cwd,
        ).map(HookRawResult::toPostToolUseResult)
        val contexts = completed.flatMap { result -> result.additionalContexts }
        val feedback = completed
            .mapNotNull { result -> result.feedback }
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString("\n\n")
        return if (completed.any { result -> result is PostToolUseResult.Block }) {
            PostToolUseResult.Block(
                feedback = feedback ?: "PostToolUse hook blocked the tool result",
                additionalContexts = contexts,
            )
        } else {
            PostToolUseResult.Continue(additionalContexts = contexts, feedback = feedback)
        }
    }

    override suspend fun onPermissionRequest(request: PermissionRequest): PermissionRequestResult {
        val hooks = currentHooks()
        val invocation = request.invocation
        val context = invocation.context
        val session = context.session
        val completed = shellClient.runHooks(
            hooks = hooks.permissionRequest.matching(invocation.matcherInputs()),
            inputJson = HookJson.encodeToString(
                PermissionRequestCommandInputWire(
                    sessionId = session.sessionId,
                    turnId = context.turnId,
                    transcriptPath = null,
                    cwd = session.cwd.toString(),
                    model = session.model,
                    permissionMode = session.permissionMode.wireName,
                    toolName = invocation.toolName,
                    toolInput = invocation.input,
                ),
            ),
            cwd = session.cwd,
        ).map(HookRawResult::toPermissionRequestResult)
        completed.firstNotNullOfOrNull { result -> result as? PermissionRequestResult.Deny }?.let { denied ->
            return denied
        }
        return if (completed.any { result -> result == PermissionRequestResult.Allow }) {
            PermissionRequestResult.Allow
        } else {
            PermissionRequestResult.NoDecision
        }
    }

    override suspend fun onPreCompact(request: CompactionHookRequest): CompactionHookResult =
        runCompactionHooks(
            hooks = currentHooks().preCompact,
            request = request,
            input = request.toPreCompactCommandInputWire(),
        )

    override suspend fun onPostCompact(request: CompactionHookRequest): CompactionHookResult =
        runCompactionHooks(
            hooks = currentHooks().postCompact,
            request = request,
            input = request.toPostCompactCommandInputWire(),
        )

    private suspend fun runCompactionHooks(
        hooks: List<ExecutableHook>,
        request: CompactionHookRequest,
        input: CompactCommandInputWire,
    ): CompactionHookResult {
        val completed = shellClient.runHooks(
            hooks = hooks.matching(listOf(request.trigger.wireName)),
            inputJson = HookJson.encodeToString(input),
            cwd = request.context.session.cwd,
        ).map(HookRawResult::toCompactionResult)
        return completed.firstNotNullOfOrNull { result -> result as? CompactionHookResult.Stop }
            ?: CompactionHookResult.Continue
    }

    private fun currentHooks(): ResolvedHooks {
        coroutineContext.ensureActive()
        return resolvedHooks.value
    }
}

/** Creates configured hooks as a child of this scope. */
@OptIn(ExperimentalCoroutinesApi::class)
public fun CoroutineScope.CodexHooksImpl(
    settings: StateFlow<HookSettings>,
): CodexHooksImpl {
    val parentJob = requireNotNull(coroutineContext[Job]) {
        "CodexHooksImpl requires an owning CoroutineScope with a Job."
    }
    val hooksScope = CoroutineScope(
        newCoroutineContext(SupervisorJob(parentJob)),
    )
    return try {
        CodexHooksImpl(
            scope = hooksScope,
            settings = settings,
        )
    } catch (failure: Throwable) {
        hooksScope.cancel()
        throw failure
    }
}

private fun HookToolInvocation.matcherInputs(): List<String> =
    listOf(toolName) + matcherAliases
