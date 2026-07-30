package io.github.stream29.kodex.hook.impl

import io.github.stream29.kodex.hook.contract.KodexHooks
import io.github.stream29.kodex.hook.contract.HookSettings
import io.github.stream29.kodex.hook.contract.compaction.CompactionHookRequest
import io.github.stream29.kodex.hook.contract.tool.HookToolInvocation
import io.github.stream29.kodex.hook.contract.approval.PermissionRequest
import io.github.stream29.kodex.hook.contract.approval.PermissionRequestResult
import io.github.stream29.kodex.hook.contract.tool.PostToolUseRequest
import io.github.stream29.kodex.hook.contract.tool.PreToolUseResult
import io.github.stream29.kodex.hook.contract.turn.StopRequest
import io.github.stream29.kodex.hook.contract.turn.StopResult
import io.github.stream29.kodex.hook.contract.turn.UserPromptSubmitRequest
import io.github.stream29.kodex.hook.contract.turn.UserPromptSubmitResult
import io.github.stream29.kodex.hook.impl.projection.CompactCommandInputWire
import io.github.stream29.kodex.hook.impl.projection.HookJson
import io.github.stream29.kodex.hook.impl.projection.PermissionRequestCommandInputWire
import io.github.stream29.kodex.hook.impl.projection.PostToolUseCommandInputWire
import io.github.stream29.kodex.hook.impl.projection.PreToolUseCommandInputWire
import io.github.stream29.kodex.hook.impl.projection.StopCommandInputWire
import io.github.stream29.kodex.hook.impl.projection.UserPromptSubmitCommandInputWire
import io.github.stream29.kodex.hook.impl.projection.toPostCompactCommandInputWire
import io.github.stream29.kodex.hook.impl.projection.toPermissionRequestResult
import io.github.stream29.kodex.hook.impl.projection.toPreCompactCommandInputWire
import io.github.stream29.kodex.hook.impl.projection.toPreToolUseResult
import io.github.stream29.kodex.hook.impl.projection.toStopResult
import io.github.stream29.kodex.hook.impl.projection.toUserPromptSubmitResult
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.shellclient.ShellClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * [KodexHooks] implementation using a resolved view of global settings.
 *
 * Each invocation reads one immutable resolved snapshot before selecting handlers.
 * Settings changes cannot alter that invocation after handler selection starts.
 */
public class KodexHooksImpl internal constructor(
    scope: CoroutineScope,
    settings: StateFlow<HookSettings>,
) :
    KodexHooks,
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
        val inputJson = HookJson.encodeToString(
            PreToolUseCommandInputWire(
                sessionId = session.sessionId,
                turnId = context.turnId,
                transcriptPath = null,
                cwd = session.cwd.toString(),
                model = session.model,
                permissionMode = session.permissionMode.wireName,
                toolName = invocation.toolName,
                toolInput = invocation.input,
                toolUseId = invocation.toolUseId,
            ),
        )
        for (hook in hooks.preToolUse.matching(matcherInputs)) {
            val result = shellClient.runHook(
                hook = hook,
                inputJson = inputJson,
                cwd = session.cwd,
            ).toPreToolUseResult()
            if (result is PreToolUseResult.Block) return result
        }
        return PreToolUseResult.Continue
    }

    override suspend fun onPostToolUse(request: PostToolUseRequest) {
        val hooks = currentHooks()
        val invocation = request.invocation
        val context = invocation.context
        val session = context.session
        shellClient.runHooks(
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
        )
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

    override suspend fun onPreCompact(request: CompactionHookRequest) {
        runCompactionHooks(
            hooks = currentHooks().preCompact,
            request = request,
            input = request.toPreCompactCommandInputWire(),
        )
    }

    override suspend fun onPostCompact(request: CompactionHookRequest) {
        runCompactionHooks(
            hooks = currentHooks().postCompact,
            request = request,
            input = request.toPostCompactCommandInputWire(),
        )
    }

    private suspend fun runCompactionHooks(
        hooks: List<ExecutableHook>,
        request: CompactionHookRequest,
        input: CompactCommandInputWire,
    ) {
        shellClient.runHooks(
            hooks = hooks.matching(listOf(request.trigger.wireName)),
            inputJson = HookJson.encodeToString(input),
            cwd = request.context.session.cwd,
        )
    }

    private fun currentHooks(): ResolvedHooks {
        coroutineContext.ensureActive()
        return resolvedHooks.value
    }
}

/** Creates configured hooks as a child of this scope. */
public fun CoroutineScope.KodexHooksImpl(
    settings: StateFlow<HookSettings>,
): KodexHooksImpl {
    val hooksScope = supervisorChildScope()
    return try {
        KodexHooksImpl(
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
