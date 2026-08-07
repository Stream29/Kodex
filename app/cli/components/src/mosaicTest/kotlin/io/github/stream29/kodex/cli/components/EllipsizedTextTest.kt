package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val ellipsizedTextTest by testSuite {
    test("uses measured width and remeasures after resize") {
        var width by mutableIntStateOf(6)

        runMosaicTest {
            assertEquals(
                "A你...",
                setContentAndSnapshot {
                    Box(Modifier.width(width)) {
                        EllipsizedText("A你BCDEF")
                    }
                },
            )

            width = 8
            assertEquals("A你BCDEF", awaitSnapshot())

            width = 2
            assertEquals("A", awaitSnapshot())
        }
    }
}
