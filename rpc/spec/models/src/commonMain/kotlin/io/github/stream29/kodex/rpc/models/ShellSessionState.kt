package io.github.stream29.kodex.rpc.models

import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import kotlinx.serialization.Serializable

/**
 * Value of one entry in the Agent's shell-session registry snapshot.
 *
 * The surrounding map supplies the process session ID. A completed process may remain
 * registered until its final output is consumed by the backend tool. This value contains
 * neither process-control handles nor output buffers.
 */
@Serializable
public data class ShellSessionState(
    public val arguments: ExecCommandArguments,
    public val completed: Boolean,
)
