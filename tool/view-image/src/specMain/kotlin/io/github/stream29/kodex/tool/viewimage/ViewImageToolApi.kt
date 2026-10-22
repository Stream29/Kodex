@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.viewimage

import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.utils.images.PromptImageTransformer
import io.github.stream29.kodex.utils.images.codec.HostPromptImageTransformer
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

public expect class ViewImageToolClient(
    workingDirectoryProvider: suspend () -> Path = { Path(".") },
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    transformer: PromptImageTransformer = HostPromptImageTransformer,
    canRequestOriginalImageDetail: Boolean = false,
) {
    public suspend fun view(arguments: ViewImageToolArguments): ViewImageToolOutput
}

public object ViewImageTools {
    public const val Name: String = "view_image"

    public const val Description: String =
        "View a local image file from the filesystem when visual inspection is needed. Use this for images already available on disk."

    public val spec: ResponsesApiTool
        get() = ViewImageToolImplementation.spec

    public fun toolSpec(options: ViewImageToolOptions = ViewImageToolOptions()): ResponsesApiTool =
        ViewImageToolImplementation.toolSpec(options)

    public fun createTool(
        client: ViewImageToolClient = ViewImageToolClient(),
        options: ViewImageToolOptions = ViewImageToolOptions(),
    ): Tool = ViewImageToolImplementation.createTool(client, options)
}

internal expect object ViewImageToolImplementation {
    val spec: ResponsesApiTool
    fun toolSpec(options: ViewImageToolOptions): ResponsesApiTool
    fun createTool(client: ViewImageToolClient, options: ViewImageToolOptions): Tool
}
