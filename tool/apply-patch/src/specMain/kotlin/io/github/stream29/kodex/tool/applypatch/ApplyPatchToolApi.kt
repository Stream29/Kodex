@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.applypatch

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.utils.applypatch.Patch
import io.github.stream29.kodex.utils.applypatch.PatchApplyResult
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

public expect class ApplyPatchToolClient(
    workingDirectoryProvider: suspend () -> Path = { Path(".") },
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
) {
    public suspend fun apply(patch: String): PatchApplyResult
    public suspend fun apply(patch: Patch): PatchApplyResult
}

public object ApplyPatchTools {
    public const val Name: String = "apply_patch"

    public val spec: ToolSpec
        get() = ApplyPatchToolImplementation.spec

    public fun createTool(client: ApplyPatchToolClient = ApplyPatchToolClient()): Tool =
        ApplyPatchToolImplementation.createTool(client)
}

internal expect object ApplyPatchToolImplementation {
    val spec: ToolSpec
    fun createTool(client: ApplyPatchToolClient): Tool
}

public expect class ApplyPatchTool(
    client: ApplyPatchToolClient = ApplyPatchToolClient(),
) : Tool {
    public override val spec: ToolSpec
    public override fun close()
    public override suspend fun handle(pending: PendingToolEvent): StableCleanEvent.CompletedTool
}
