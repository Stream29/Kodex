@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.kodex.agentschedule.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Persisted one-shot timer (`cron == null`) or recurring 5-field cron schedule
 * targeting [agentId].
 */
@Serializable
public data class ScheduleRecord(
    @SerialName("schedule_id")
    public val scheduleId: Long,
    @SerialName("agent_id")
    public val agentId: Int,
    @SerialName("trigger_at")
    public val triggerAt: Instant,
    public val cron: String? = null,
    public val description: String,
    @SerialName("created_at")
    public val createdAt: Instant,
)
