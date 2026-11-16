package io.github.stream29.kodex.cli.notification

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.shellclient.ProcessSession
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellClient
import io.github.stream29.kodex.utils.shellclient.ShellProcessCommand
import io.github.stream29.kodex.utils.shellclient.default
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
): Unit = withNotificationShell { execute ->
    collectNotificationHooks(notifications, hooks, workingDirectory, execute, ::logHookFailure)
}

public actual suspend fun notifyUnhandledErrorHooks(
    message: String?,
    hooks: List<NotificationHook>,
    workingDirectory: Path,
) {
    val selected = hooks.filter { NotificationHookType.StopUnhandledError in it.types }
    if (selected.isEmpty()) return
    withNotificationShell { execute ->
        runSelectedHooks(selected, workingDirectory, unhandledErrorJson(message), execute, ::logHookFailure)
    }
}

internal suspend fun collectNotificationHooks(
    notifications: Flow<Notification>,
    hooks: () -> List<NotificationHook>,
    workingDirectory: Path,
    execute: suspend (ShellProcessCommand, String) -> Unit,
    report: (String, Throwable) -> Unit,
) {
    // Subscribe directly so an UNDISPATCHED backend owner registers before publishing Ready.
    notifications.collect { notification ->
        val selected = hooks().filter { notification.type in it.types }
        if (selected.isNotEmpty()) {
            val json = Json.encodeToString<Notification>(notification)
            runSelectedHooks(selected, workingDirectory, json, execute, report)
        }
    }
}

internal suspend fun notifyUnhandledErrorHooks(
    message: String?,
    hooks: List<NotificationHook>,
    workingDirectory: Path,
    execute: suspend (ShellProcessCommand, String) -> Unit,
    report: (String, Throwable) -> Unit,
) {
    val selected = hooks.filter { NotificationHookType.StopUnhandledError in it.types }
    if (selected.isEmpty()) return
    runSelectedHooks(selected, workingDirectory, unhandledErrorJson(message), execute, report)
}

private suspend fun runSelectedHooks(
    selected: List<NotificationHook>,
    workingDirectory: Path,
    json: String,
    execute: suspend (ShellProcessCommand, String) -> Unit,
    report: (String, Throwable) -> Unit,
) {
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

private suspend fun withNotificationShell(
    block: suspend (execute: suspend (ShellProcessCommand, String) -> Unit) -> Unit,
): Unit = coroutineScope {
    val owner = supervisorChildScope()
    val client = owner.ShellClient()
    try {
        block { command, input ->
            executeNotificationProcess(command, input, client::start)
        }
    } finally {
        withContext(NonCancellable) {
            try {
                client.close()
            } finally {
                owner.cancelAndJoin()
            }
        }
    }
}

private fun unhandledErrorJson(message: String?): String =
    buildJsonObject {
        put("type", JsonPrimitive("unhandled_error"))
        put("message", message?.let(::JsonPrimitive) ?: JsonNull)
    }.toString()

private fun logHookFailure(name: String, failure: Throwable) {
    HookLogger.warn(failure) { "Notification Hook '$name' failed." }
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
        is Notification.AskUserCreated -> NotificationHookType.AskUserCreated
        is Notification.Stop.UnhandledError -> NotificationHookType.StopUnhandledError
    }

private val HookLogger = KotlinLogging.logger {}
