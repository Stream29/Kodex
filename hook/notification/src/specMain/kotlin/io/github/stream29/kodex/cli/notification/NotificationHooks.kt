package io.github.stream29.kodex.cli.notification

import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.rpc.models.NotificationHook
import kotlinx.coroutines.flow.Flow
import kotlinx.io.files.Path

/**
 * One serial consumer per backend execution owner.
 *
 * Each received notification captures one settings snapshot and runs matching commands
 * in list order with the notification JSON on stdin. The platform default Shell is used,
 * not backend shell settings. Each command has a ten-second input/exit budget; failures
 * are diagnosed locally and do not change backend execution or end the subscription.
 * Cancellation propagates after terminating and awaiting cleanup of the current process;
 * no queued notifications are drained when the execution owner shuts down.
 */
public expect suspend fun collectNotificationHooks(
    notifications: Flow<Notification>,
    hooks: () -> List<NotificationHook>,
    workingDirectory: Path,
)

/**
 * Runs configured `stop_unhandled_error` hooks for a single local unhandled error [message]
 * with JSON `{"type":"unhandled_error","message":...}` on stdin.
 */
public expect suspend fun notifyUnhandledErrorHooks(
    message: String?,
    hooks: List<NotificationHook>,
    workingDirectory: Path,
)
