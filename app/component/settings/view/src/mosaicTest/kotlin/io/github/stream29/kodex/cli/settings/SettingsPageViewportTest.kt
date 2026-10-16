package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.cli.components.ScrollState
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val settingsPageViewportTest by testSuite {
    test("showsScrolledContentInsideBoundedSettingsViewport") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(20).height(3)) {
                    SettingsPageViewport(
                        width = 20,
                        scrollState = ScrollState(initial = 2),
                    ) {
                        repeat(5) { index ->
                            Text("setting-$index")
                        }
                    }
                }
            }

            assertFalse("setting-0" in snapshot, snapshot)
            assertFalse("setting-1" in snapshot, snapshot)
            assertTrue("setting-2" in snapshot, snapshot)
            assertTrue("setting-3" in snapshot, snapshot)
            assertTrue("setting-4" in snapshot, snapshot)
        }
    }
}
