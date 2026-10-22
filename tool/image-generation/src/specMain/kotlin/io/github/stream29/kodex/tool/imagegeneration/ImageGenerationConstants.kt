package io.github.stream29.kodex.tool.imagegeneration

import io.github.stream29.kodex.openai.OpenAiModelId

public const val ImageGenNamespace: String = "image_gen"
public const val ImageGenToolName: String = "imagegen"
public val ImageGenDefaultModel: OpenAiModelId = OpenAiModelId("gpt-image-2")
public const val ImageGenMaxEditImages: Int = 5
