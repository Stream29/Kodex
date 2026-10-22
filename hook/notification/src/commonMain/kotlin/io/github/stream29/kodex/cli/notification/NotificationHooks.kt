package io.github.stream29.kodex.cli.notification

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.rpc.models.NotificationHookType
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.shellclient.ProcessSession
import io.github.stream29.kodex.utils.shellclient.ShellClient
import io.github.stream29.kodex.utils.shellclient.ShellProcessCommand
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.default
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

public actual suspend fun collectNotificationHooks(
    notifications: Flow<Notification>,
    hooks: () -> List<NotificationHook>,
    workingDirectory: Path,
    unhandledErrors: Flow<String?>?,
): Unit = collectNotificationHooks(notifications, hooks, workingDirectory, ::runNotificationCommand, unhandledErrors) { name, failure ->
    HookLogger.warn(failure) { "Notification Hook '$name' failed." }
}

internal suspend fun collectNotificationHooks(
    notifications: Flow<Notification>,
    hooks: () -> List<NotificationHook>,
    workingDirectory: Path,
    execute: suspend (ShellProcessCommand, String) -> Unit,
    unhandledErrors: Flow<String?>? = null,
    report: (String, Throwable) -> Unit,
) {
    val stops = notifications.map { notification ->
        notification.type to Json.encodeToString<Notification>(notification)
    }
    // With no local-error source, subscribe directly. An UNDISPATCHED backend
    // owner must register before publishing Ready, not enqueue merge children.
    val events = if (unhandledErrors == null) stops else merge(
        stops,
        unhandledErrors.map { message ->
            NotificationHookType.StopUnhandledError to buildJsonObject {
                put("type", JsonPrimitive("unhandled_error"))
                put("message", message?.let(::JsonPrimitive) ?: JsonNull)
            }.toString()
        },
    )
    events.collect { (type, json) ->
        val selected = hooks().filter { type in it.types }
        for (hook in selected) {
            try {
                execute(ShellProcessCommand(hook.command, workingDirectory, shell = Shell.default), json)
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                runCatching { report(hook.name, timeout) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                runCatching { report(hook.name, failure) }
            }
        }
    }
}

internal suspend fun runNotificationCommand(command: ShellProcessCommand, input: String): Unit = coroutineScope {
    val owner = supervisorChildScope()
    val client = owner.ShellClient()
    try {
        executeNotificationProcess(command, input, client::start)
    } finally {
        withContext(NonCancellable) {
            try { client.close() } finally { owner.cancelAndJoin() }
        }
    }
}

/** Real shell cleanup and fake-process tests exercise the same transfer boundary. */
internal suspend fun executeNotificationProcess(
    command: ShellProcessCommand,
    input: String,
    start: suspend (ShellProcessCommand) -> ProcessSession,
) {
    var process: ProcessSession? = null
    var failure: Throwable? = null
    try {
        withTimeout(10.seconds) {
            val started = start(command).also { process = it }
            started.stdin.send(input)
            started.stdin.close()
            val code = started.exitCode.await()
            check(code == 0) { "Notification command exited with code $code." }
        }
    } catch (cause: Throwable) {
        failure = cause
        throw cause
    } finally {
        withContext(NonCancellable) {
            process?.let { started ->
                try {
                    try { started.close() } finally { started.exitCode.await() }
                } catch (cleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }
}

private val Notification.type: NotificationHookType
    get() = when (this) {
        is Notification.Stop.AssistantMessage -> NotificationHookType.StopAssistantMessage
        is Notification.Stop.RequestUserInput -> NotificationHookType.StopRequestUserInput
        is Notification.Stop.SuggestSubagent -> NotificationHookType.StopSuggestSubagent
        is Notification.Stop.UnhandledError -> NotificationHookType.StopUnhandledError
    }

private val HookLogger = KotlinLogging.logger {}
