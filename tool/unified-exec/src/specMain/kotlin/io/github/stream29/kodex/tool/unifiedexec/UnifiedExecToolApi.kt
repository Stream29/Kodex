@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.unifiedexec

import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.tool.contract.Tool
import kotlinx.schema.json.ObjectPropertyDefinition

public expect val ExecCommandParametersSchema: ObjectPropertyDefinition

public object UnifiedExecTools {
    public const val ExecCommandName: String = "exec_command"

    public const val ExecCommandDescription: String =
        "Runs a command in a PTY, returning output or a session ID for ongoing interaction."

    public val execCommandSpec: ResponsesApiTool
        get() = UnifiedExecToolImplementation.execCommandSpec

    /** Creates the unified-exec model tool around [client]. */
    public fun createTool(client: UnifiedExecClient): Tool =
        UnifiedExecToolImplementation.createTool(client)
}

internal expect object UnifiedExecToolImplementation {
    val execCommandSpec: ResponsesApiTool
    fun createTool(client: UnifiedExecClient): Tool
}
