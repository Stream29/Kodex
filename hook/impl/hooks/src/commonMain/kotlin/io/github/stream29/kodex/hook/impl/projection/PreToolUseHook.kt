package io.github.stream29.kodex.hook.impl.projection

import io.github.stream29.kodex.hook.contract.tool.PreToolUseResult
import io.github.stream29.kodex.hook.impl.HookRawResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class PreToolUsePayload(
    @SerialName("tool_name") val toolName: String,
    @SerialName("tool_input") val toolInput: JsonElement,
    @SerialName("tool_use_id") val toolUseId: String,
)

@Serializable
internal data class PreToolUseOutput(
    val action: PreToolUseAction = PreToolUseAction.Continue,
    val reason: String? = null,
)

@Serializable
internal enum class PreToolUseAction {
    @SerialName("continue")
    Continue,

    @SerialName("block")
    Block,
}

internal fun HookRawResult.toPreToolUseResult(): PreToolUseResult {
    if (exitCode != 0 || stdout.isBlank()) return PreToolUseResult.Continue
    val output = decodeHookOutputOrNull<PreToolUseOutput>(stdout)
        ?: return PreToolUseResult.Continue
    return when (output.action) {
        PreToolUseAction.Continue -> PreToolUseResult.Continue
        PreToolUseAction.Block ->
            output.reason
                ?.trimmedNonEmpty()
                ?.let(PreToolUseResult::Block)
                ?: PreToolUseResult.Block()
    }
}
