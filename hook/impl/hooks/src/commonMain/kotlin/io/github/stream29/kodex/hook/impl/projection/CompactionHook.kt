package io.github.stream29.kodex.hook.impl.projection

import io.github.stream29.kodex.hook.contract.compaction.CompactionHookRequest
import kotlinx.serialization.Serializable

@Serializable
internal data class CompactionPayload(
    val trigger: String,
)

internal fun CompactionHookRequest.toCompactionPayload(): CompactionPayload =
    CompactionPayload(trigger = trigger.wireName)
