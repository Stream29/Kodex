package io.github.stream29.kodex.tool.imagegeneration

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.ImageData
import io.github.stream29.kodex.openai.ImageResponse
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val imageGenerationToolClientTest by testSuite {
    test("run generates image with mock client") {
        val toolClient = ImageGenerationToolClient(
            client = mockOpenAiClient {
                generateImage { request ->
                    assertEquals("draw", request.prompt)
                    OpenAiResult.Success(
                        ImageResponse(
                            created = 1,
                            data = listOf(ImageData("generated-image")),
                        ),
                    )
                }
            },
        )

        val output = toolClient.run(ImageGenToolArguments(prompt = "draw"))

        assertEquals(GeneratedImageOutput(result = "generated-image"), output)
    }

    test("too many referenced images fail before calling api") {
        val toolClient = ImageGenerationToolClient(client = mockOpenAiClient())

        assertFailsWith<ImageGenerationToolException> {
            toolClient.run(
                ImageGenToolArguments(
                    prompt = "edit",
                    referencedImagePaths = List(ImageGenMaxEditImages + 1) { "/tmp/$it.png" },
                ),
            )
        }
    }

    test("conversation image selection requires agent loop history") {
        val toolClient = ImageGenerationToolClient(client = mockOpenAiClient())

        assertFailsWith<ImageGenerationToolException> {
            toolClient.run(
                ImageGenToolArguments(
                    prompt = "edit",
                    numLastImagesToInclude = 1,
                ),
            )
        }
    }
}
