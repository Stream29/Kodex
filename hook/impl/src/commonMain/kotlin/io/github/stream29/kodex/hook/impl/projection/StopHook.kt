package io.github.stream29.kodex.hook.impl.projection

import io.github.stream29.kodex.hook.contract.turn.HookPromptFragment
import io.github.stream29.kodex.hook.contract.turn.StopResult
import io.github.stream29.kodex.hook.impl.HookRawResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class StopPayload(
    @SerialName("stop_hook_active") val stopHookActive: Boolean,
    @SerialName("last_assistant_message") val lastAssistantMessage: String?,
)

@Serializable
internal data class StopOutput(
    val action: StopAction = StopAction.Finish,
    val prompt: String? = null,
    val reason: String? = null,
)

@Serializable
internal enum class StopAction {
    @SerialName("finish")
    Finish,

    @SerialName("continue")
    Continue,

    @SerialName("stop")
    Stop,
}

internal fun HookRawResult.toStopResult(hookName: String): StopResult {
    if (exitCode != 0 || stdout.isBlank()) return StopResult.Finish
    val output = decodeHookOutputOrNull<StopOutput>(stdout) ?: return StopResult.Finish
    return when (output.action) {
        StopAction.Finish -> StopResult.Finish
        StopAction.Continue ->
            output.prompt
                ?.trimmedNonEmpty()
                ?.let { prompt ->
                    StopResult.Continue(
                        listOf(
                            HookPromptFragment(
                                text = prompt,
                                hookRunId = hookName,
                            ),
                        ),
                    )
                }
                ?: StopResult.Finish

        StopAction.Stop -> StopResult.Stop(output.reason?.trimmedNonEmpty())
    }
}
