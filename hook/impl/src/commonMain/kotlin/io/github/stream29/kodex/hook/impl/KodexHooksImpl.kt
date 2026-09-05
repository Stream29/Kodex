package io.github.stream29.kodex.hook.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.hook.contract.HookSettings
import io.github.stream29.kodex.hook.contract.HookType
import io.github.stream29.kodex.hook.contract.KodexHooks
import io.github.stream29.kodex.hook.contract.compaction.CompactionHookRequest
import io.github.stream29.kodex.hook.contract.tool.HookToolInvocation
import io.github.stream29.kodex.hook.contract.tool.PostToolUseRequest
import io.github.stream29.kodex.hook.contract.tool.PreToolUseResult
import io.github.stream29.kodex.hook.contract.turn.StopRequest
import io.github.stream29.kodex.hook.contract.turn.StopResult
import io.github.stream29.kodex.hook.contract.turn.UserPromptSubmitRequest
import io.github.stream29.kodex.hook.contract.turn.UserPromptSubmitResult
import io.github.stream29.kodex.hook.impl.projection.PostToolUsePayload
import io.github.stream29.kodex.hook.impl.projection.PreToolUsePayload
import io.github.stream29.kodex.hook.impl.projection.StopPayload
import io.github.stream29.kodex.hook.impl.projection.UserPromptSubmitPayload
import io.github.stream29.kodex.hook.impl.projection.encodeHookInput
import io.github.stream29.kodex.hook.impl.projection.toCompactionPayload
import io.github.stream29.kodex.hook.impl.projection.toPreToolUseResult
import io.github.stream29.kodex.hook.impl.projection.toStopResult
import io.github.stream29.kodex.hook.impl.projection.toUserPromptSubmitResult
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.logging.global
import io.github.stream29.kodex.utils.shellclient.ShellClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.io.files.Path

/**
 * [KodexHooks] implementation using a resolved view of global settings.
 *
 * Each invocation reads one immutable resolved snapshot before selecting commands.
 * Settings changes cannot alter that invocation after command selection starts.
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
        val hooks = currentHooks()[HookType.UserPromptSubmit]
        val contexts = mutableListOf<String>()
        val payload = UserPromptSubmitPayload(prompt = request.prompt)
        for (hook in hooks) {
            val result = shellClient.runHook(
                hook = hook,
                input = encodeHookInput(
                    hook = hook,
                    type = HookType.UserPromptSubmit,
                    context = request.context,
                    payload = payload,
                ),
                cwd = request.context.session.cwd,
            ).toUserPromptSubmitResult()
            contexts += result.additionalContexts
            if (result is UserPromptSubmitResult.Stop) {
                return UserPromptSubmitResult.Stop(result.reason, contexts)
            }
        }
        return UserPromptSubmitResult.Continue(contexts)
    }

    override suspend fun onStop(request: StopRequest): StopResult {
        val hooks = currentHooks()[HookType.Stop]
        val payload = StopPayload(
            stopHookActive = request.stopHookActive,
            lastAssistantMessage = request.lastAssistantMessage,
        )
        for (hook in hooks) {
            val result = shellClient.runHook(
                hook = hook,
                input = encodeHookInput(
                    hook = hook,
                    type = HookType.Stop,
                    context = request.context,
                    payload = payload,
                ),
                cwd = request.context.session.cwd,
            ).toStopResult(hook.name)
            if (result != StopResult.Finish) return result
        }
        return StopResult.Finish
    }

    override suspend fun onPreToolUse(invocation: HookToolInvocation): PreToolUseResult {
        val hooks = currentHooks()[HookType.PreToolUse]
        val payload = PreToolUsePayload(
            toolName = invocation.toolName,
            toolInput = invocation.input,
            toolUseId = invocation.toolUseId,
        )
        for (hook in hooks) {
            val result = shellClient.runHook(
                hook = hook,
                input = encodeHookInput(
                    hook = hook,
                    type = HookType.PreToolUse,
                    context = invocation.context,
                    payload = payload,
                ),
                cwd = invocation.context.session.cwd,
            ).toPreToolUseResult()
            if (result is PreToolUseResult.Block) return result
        }
        return PreToolUseResult.Continue
    }

    override suspend fun onPostToolUse(request: PostToolUseRequest) {
        val invocation = request.invocation
        val payload = PostToolUsePayload(
            toolName = invocation.toolName,
            toolInput = invocation.input,
            toolResponse = request.response,
            toolUseId = invocation.toolUseId,
        )
        shellClient.runHooks(
            hooks = currentHooks()[HookType.PostToolUse],
            cwd = invocation.context.session.cwd,
        ) { hook ->
            encodeHookInput(
                hook = hook,
                type = HookType.PostToolUse,
                context = invocation.context,
                payload = payload,
            )
        }
    }

    override suspend fun onPreCompact(request: CompactionHookRequest) {
        runCompactionHooks(HookType.PreCompact, request)
    }

    override suspend fun onPostCompact(request: CompactionHookRequest) {
        runCompactionHooks(HookType.PostCompact, request)
    }

    override suspend fun onUnhandledError(message: String?, cwd: Path) {
        val hooks = currentHooks()[HookType.UnhandledError]
        if (hooks.isEmpty()) return
        val input = message.orEmpty()
        coroutineScope {
            hooks.map { hook ->
                async {
                    try {
                        val result = shellClient.runHook(hook, input, cwd)
                        if (result.exitCode != 0) {
                            logger.error {
                                "Unhandled error hook '${hook.name}' failed " +
                                    "(exitCode=${result.exitCode ?: "unavailable"})."
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        logger.error(failure) { "Unhandled error hook '${hook.name}' failed." }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun runCompactionHooks(
        type: HookType,
        request: CompactionHookRequest,
    ) {
        val payload = request.toCompactionPayload()
        shellClient.runHooks(
            hooks = currentHooks()[type],
            cwd = request.context.session.cwd,
        ) { hook ->
            encodeHookInput(
                hook = hook,
                type = type,
                context = request.context,
                payload = payload,
            )
        }
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

private val logger by lazy {
    KotlinLogging.logger {}.global()
}
