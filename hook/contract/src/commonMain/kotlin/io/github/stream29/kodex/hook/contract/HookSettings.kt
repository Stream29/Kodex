package io.github.stream29.kodex.hook.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Narrow global-settings view required by a configured Hook implementation. */
public interface HookSettings {
    /** Latest complete Hook configuration snapshot. */
    public val hooks: HookConfiguration
}

/** Ordered Hook definitions keyed by their globally unique names. */
public typealias HookConfiguration = Map<String, HookBody>

/** One Hook command and the runtime boundary that invokes it. */
@Serializable
public data class HookBody(
    public val type: HookType,
    public val command: String,
) {
    init {
        require(command.isNotBlank()) { "A Hook command must not be blank." }
    }
}

/** Hook invocation boundaries implemented by Kodex. */
@Serializable
public enum class HookType {
    @SerialName("pre_tool_use")
    PreToolUse,

    @SerialName("post_tool_use")
    PostToolUse,

    @SerialName("user_prompt_submit")
    UserPromptSubmit,

    @SerialName("stop")
    Stop,

    @SerialName("pre_compact")
    PreCompact,

    @SerialName("post_compact")
    PostCompact,
}
