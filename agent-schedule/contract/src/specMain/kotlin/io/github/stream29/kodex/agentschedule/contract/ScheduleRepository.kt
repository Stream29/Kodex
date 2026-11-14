@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.kodex.agentschedule.contract

import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Persistence repository for external one-shot timers and recurring cron schedules.
 *
 * Schedule rows live outside `agent_commits` and are unaffected by conversation `revert`
 * or `fork`. Callers consuming due schedules via [listDue] must durably deliver the event
 * to `AgentEventBus.submitScheduleTrigger` before calling [delete] (for `cron == null`)
 * or [advance] (for `cron != null`) so triggers are not lost across crashes.
 */
public interface ScheduleRepository {
    /** Earliest [ScheduleRecord.triggerAt] across all persisted schedules, or `null` when empty. */
    public val nextTriggerAt: StateFlow<Instant?>

    /**
     * Creates and persists a one-shot (`cron == null`) or recurring 5-field cron schedule
     * targeting [agentId], updating [nextTriggerAt].
     *
     * @throws IllegalArgumentException if [cron] is non-null and not a valid 5-field cron expression.
     */
    public suspend fun create(
        agentId: Int,
        triggerAt: Instant,
        cron: String? = null,
        description: String,
        createdAt: Instant = Clock.System.now(),
    ): ScheduleRecord

    /** Lists active schedules targeting [agentId], ordered ascending by `(triggerAt, scheduleId)`. */
    public suspend fun list(agentId: Int): List<ScheduleRecord>

    /** Lists all schedules with `triggerAt <= now`, ordered ascending by `(triggerAt, scheduleId)`. */
    public suspend fun listDue(now: Instant): List<ScheduleRecord>

    /**
     * Advances a recurring [scheduleId] to [nextTriggerAt] after successful event delivery,
     * returning `true` if [scheduleId] still existed or `false` if concurrently deleted.
     */
    public suspend fun advance(
        scheduleId: Long,
        nextTriggerAt: Instant,
    ): Boolean

    /** Deletes [scheduleId], returning `true` if it existed. */
    public suspend fun delete(scheduleId: Long): Boolean
}

/**
 * Validates [cron] as a standard 5-field cron expression (`minute hour day-of-month month day-of-week`)
 * and returns the earliest trigger [Instant] strictly after [after] in [timeZone].
 *
 * @throws IllegalArgumentException if [cron] is invalid.
 */
public expect fun nextCronTriggerAt(
    cron: String,
    after: Instant,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Instant
