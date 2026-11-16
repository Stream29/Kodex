package io.github.stream29.kodex.utils.images

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val promptImageDimensionsTest by testSuite {
    test("fit within max dimension preserves small dimensions and scales larger ones") {
        assertEquals(
            ImageDimensions(64, 32),
            ImageDimensions(64, 32).fitWithinMaxDimension(PromptImages.MaxDimension),
        )
        assertEquals(
            ImageDimensions(1024, 512),
            ImageDimensions(2048, 1024).fitWithinMaxDimension(1024),
        )
        assertFailsWith<IllegalArgumentException> {
            ImageDimensions(64, 32).fitWithinMaxDimension(0)
        }
    }
}
