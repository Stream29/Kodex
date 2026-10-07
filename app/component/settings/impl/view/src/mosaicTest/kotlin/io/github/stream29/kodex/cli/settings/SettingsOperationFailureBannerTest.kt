package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val settingsOperationFailureBannerTest by testSuite {
    test("Settings errors render a generic message without remote exception details") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    SettingsOperationFailureBanner(failed = true, onDismiss = {})
                }
            }
            assertTrue("Could not confirm a Settings operation" in snapshot, snapshot)
            assertTrue("[Dismiss]" in snapshot, snapshot)
            assertFalse("private-value-must-not-be-rendered" in snapshot, snapshot)
        }
    }

    test("Settings errors do not leave a banner after acknowledgement") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    SettingsOperationFailureBanner(failed = false, onDismiss = {})
                }
            }
            assertFalse("Could not confirm a Settings operation" in snapshot, snapshot)
        }
    }
}
