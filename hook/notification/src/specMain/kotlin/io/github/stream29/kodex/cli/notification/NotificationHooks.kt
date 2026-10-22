package io.github.stream29.kodex.cli.notification

import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.rpc.models.NotificationHook
import kotlinx.coroutines.flow.Flow
import kotlinx.io.files.Path

/**
 * One serial consumer per execution owner. The backend supplies its Home cwd
 * for business notifications; the frontend supplies its startup cwd for local
 * errors. Neither owner creates a consumer per tab.
 *
 * Each received notification captures one settings snapshot and runs matching commands
 * in list order with the notification JSON on stdin. The platform default Shell is used,
 * not backend shell settings. Each command has a ten-second input/exit budget; failures
 * are diagnosed locally and do not change backend execution or end the subscription.
 * Cancellation propagates after terminating and awaiting cleanup of the current process;
 * no queued notifications are drained when the execution owner shuts down.
 *
 * [unhandledErrors] contains frontend-local errors and actual warning messages.
 * Cancellation is excluded by the reporter.
 * They select the existing Unhandled error Hook and receive JSON
 * `{"type":"unhandled_error","message":...}` on stdin. They are not backend Agent
 * Stops and have no fabricated session index. Backend notification JSON is unchanged.
 * Both sources share this single serial executor and the same snapshot/timeout rules.
 */
public expect suspend fun collectNotificationHooks(
    notifications: Flow<Notification>,
    hooks: () -> List<NotificationHook>,
    workingDirectory: Path,
    unhandledErrors: Flow<String?>? = null,
)
