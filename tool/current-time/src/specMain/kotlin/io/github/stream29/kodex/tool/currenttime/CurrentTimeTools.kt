@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.currenttime

import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.tool.contract.Tool
import kotlin.time.Clock

public const val CurrentTimeNamespace: String = "clock"
public const val CurrentTimeToolName: String = "curr_time"

/** Local current-time source used by `clock.curr_time`. */
public expect class CurrentTimeToolClient(
    clock: Clock = Clock.System,
) {
    public fun currentTime(): String
}

public object CurrentTimeTools {
    public const val NamespaceDescription: String = "Tools for reading and waiting on time."
    public const val Description: String = "Return the current time in UTC."

    public val spec: ToolSpec
        get() = CurrentTimeToolImplementation.spec

    public fun createTool(client: CurrentTimeToolClient = CurrentTimeToolClient()): Tool =
        CurrentTimeToolImplementation.createTool(client)
}

internal expect object CurrentTimeToolImplementation {
    val spec: ToolSpec
    fun createTool(client: CurrentTimeToolClient): Tool
}
