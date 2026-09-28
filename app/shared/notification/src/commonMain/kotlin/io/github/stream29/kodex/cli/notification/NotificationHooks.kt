package io.github.stream29.kodex.cli.notification

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.rpc.models.NotificationHookType
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.shellclient.ProcessSession
import io.github.stream29.kodex.utils.shellclient.ShellClient
import io.github.stream29.kodex.utils.shellclient.ShellProcessCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * One serial frontend consumer. The caller owns this collection, supplies the
 * resolved startup cwd, and must not create one consumer per tab.
 */
public suspend fun collectNotificationHooks(
    notifications: Flow<Notification>,
    settings: StateFlow<CliFrontendSettings>,
    workingDirectory: Path,
): Unit = collectNotificationHooks(notifications, settings, workingDirectory, ::runNotificationCommand) { name, failure ->
    HookLogger.warn(failure) { "Notification Hook '$name' failed." }
}

internal suspend fun collectNotificationHooks(
    notifications: Flow<Notification>,
    settings: StateFlow<CliFrontendSettings>,
    workingDirectory: Path,
    execute: suspend (ShellProcessCommand, String) -> Unit,
    report: (String, Throwable) -> Unit,
) {
    notifications.collect { notification ->
        val selected = settings.value.hooks.filter { notification.type in it.types }
        val json = Json.encodeToString<Notification>(notification)
        for (hook in selected) {
            try {
                execute(ShellProcessCommand(hook.command, workingDirectory), json)
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
