@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.readmedia

import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.utils.images.PromptImageTransformer
import io.github.stream29.kodex.utils.images.codec.HostPromptImageTransformer
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

public expect class ReadMediaToolClient(
    workingDirectoryProvider: suspend () -> Path = { Path(".") },
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    transformer: PromptImageTransformer = HostPromptImageTransformer,
    canRequestOriginalImageDetail: Boolean = true,
) {
    public suspend fun read(arguments: ReadMediaToolArguments): ReadMediaToolOutput
}

public object ReadMediaTools {
    public const val Name: String = "read_media"

    public const val Description: String =
        "Read a local image file from the filesystem into the model context when visual inspection is needed."

    public val spec: ResponsesApiTool
        get() = ReadMediaToolImplementation.spec

    public fun createTool(
        client: ReadMediaToolClient = ReadMediaToolClient(),
    ): Tool = ReadMediaToolImplementation.createTool(client)
}

internal expect object ReadMediaToolImplementation {
    val spec: ResponsesApiTool
    fun createTool(client: ReadMediaToolClient): Tool
}
