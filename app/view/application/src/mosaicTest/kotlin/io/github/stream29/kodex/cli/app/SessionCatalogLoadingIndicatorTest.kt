package io.github.stream29.kodex.cli.app

import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val sessionCatalogLoadingIndicatorTest by testSuite {
    test("loadingIndicatorAnimatesWhileCatalogLoads") {
        runMosaicTest {
            val initial = setContentAndSnapshot {
                SessionCatalogLoadingIndicator()
            }
            assertEquals("⠋ Loading sessions…", initial)

            var next = initial
            repeat(20) {
                if (next == initial) next = awaitSnapshot()
            }
            assertEquals("⠙ Loading sessions…", next)
        }
    }
}
