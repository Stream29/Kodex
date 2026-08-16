package io.github.stream29.kodex.hook.impl.projection

import io.github.stream29.kodex.hook.contract.turn.UserPromptSubmitResult
import io.github.stream29.kodex.hook.impl.HookRawResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class UserPromptSubmitPayload(
    val prompt: String,
)

@Serializable
internal data class UserPromptSubmitOutput(
    val action: UserPromptSubmitAction = UserPromptSubmitAction.Continue,
    val reason: String? = null,
    val context: String? = null,
)

@Serializable
internal enum class UserPromptSubmitAction {
    @SerialName("continue")
    Continue,

    @SerialName("block")
    Block,
}

internal fun HookRawResult.toUserPromptSubmitResult(): UserPromptSubmitResult {
    if (exitCode != 0 || stdout.isBlank()) return UserPromptSubmitResult.Continue()
    val output = decodeHookOutputOrNull<UserPromptSubmitOutput>(stdout)
        ?: return UserPromptSubmitResult.Continue()
    val contexts = output.context
        ?.takeIf(String::isNotBlank)
        ?.let(::listOf)
        .orEmpty()
    return when (output.action) {
        UserPromptSubmitAction.Continue -> UserPromptSubmitResult.Continue(contexts)
        UserPromptSubmitAction.Block -> UserPromptSubmitResult.Stop(
            reason = output.reason?.trimmedNonEmpty(),
            additionalContexts = contexts,
        )
    }
}
