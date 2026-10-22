package io.github.stream29.kodex.tool.imagegeneration

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

private const val NamespaceDescription: String = ImageGenerationTools.DefaultNamespaceDescription
private const val Description: String = ImageGenerationTools.ImageGenDescription

val imageGenerationContractConstantsTest by testSuite {
    test("tool descriptions remain compile-time constants") {
        assertEquals("Tools in the image_gen namespace.", NamespaceDescription)
        assertEquals(ImageGenerationTools.ImageGenDescription, Description)
    }
}
