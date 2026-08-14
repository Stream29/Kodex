package io.github.stream29.kodex.cli.app

import com.jakewharton.mosaic.testing.runMosaicTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionCatalogLoadingIndicatorTest {
    @Test
    fun loadingIndicatorAnimatesWhileCatalogLoads() = runTest {
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
