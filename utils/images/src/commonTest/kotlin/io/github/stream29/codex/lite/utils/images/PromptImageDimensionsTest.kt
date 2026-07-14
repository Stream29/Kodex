package io.github.stream29.codex.lite.utils.images

import de.infix.testBalloon.framework.core.testSuite

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue



val promptImageDimensionsTest by testSuite {
    test("fits prompt image limits checks patch budget") {
        val limits = PromptImageResizeLimits(maxDimension = PromptImages.MaxDimension, maxPatches = 4)

        assertTrue(ImageDimensions(64, 64).fitsPromptImageLimits(limits))
        assertFalse(ImageDimensions(65, 64).fitsPromptImageLimits(limits))
    }

    test("fit prompt image limits respects dimension budget") {
        assertEquals(
            ImageDimensions(1024, 512),
            ImageDimensions(2048, 1024)
                .fitPromptImageLimits(PromptImageResizeLimits(maxDimension = 1024, maxPatches = 1024)),
        )
    }

    test("fit prompt image limits respects patch budget") {
        assertEquals(
            ImageDimensions(1600, 1600),
            ImageDimensions(2048, 2048)
                .fitPromptImageLimits(PromptImageResizeLimits(maxDimension = 2048, maxPatches = 2500)),
        )
    }
}
