package io.github.stream29.kodex.rpc.models

import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import kotlinx.serialization.Serializable

/**
 * Value of one entry in the Agent's shell-session registry snapshot.
 *
 * The surrounding map supplies the process session ID. [exitCode] is `null` while running and
 * non-null once completed ([completed] derives from `exitCode != null`). A completed process
 * remains registered during its post-exit retention window. This value contains neither
 * process-control handles nor output buffers.
 */
@Serializable
public data class ShellSessionState(
    public val arguments: ExecCommandArguments,
    public val exitCode: Int? = null,
) {
    public val completed: Boolean
        get() = exitCode != null
}
