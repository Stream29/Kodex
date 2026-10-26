package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.UnderlineStyle
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.withTimeoutOrNull

val ellipsizedTextTest by testSuite {
    test("settles at idle and after text and measured width changes") {
        var width by mutableIntStateOf(6)
        var value by mutableStateOf("A你BCDEF")

        runMosaicTest {
            try {
                assertEquals(
                    "A你...",
                    setContentAndSnapshot {
                        Box(Modifier.width(width)) {
                            EllipsizedText(value)
                        }
                    },
                )
                assertEllipsizedTextSettles("A你...")

                val changes = listOf(
                    Triple("A你BCDEF", 8, "A你BCDEF"),
                    Triple("A你BCDEF", 2, "A"),
                    Triple("XYZ123456789", 6, "XYZ..."),
                    Triple("XYZ123456", 6, "XYZ..."),
                    Triple("XYZ123456", 9, "XYZ123456"),
                    Triple("e\u0301你BCDEF", 6, "e\u0301你..."),
                    Triple("👩‍💻ABCDE", 6, "👩‍💻A..."),
                    Triple("👩‍💻ABCDE", 0, ""),
                    Triple("", 6, ""),
                    Triple("A你BCDEF", 6, "A你..."),
                )
                changes.forEach { (nextValue, nextWidth, expected) ->
                    value = nextValue
                    width = nextWidth
                    assertEquals(expected, awaitSnapshot())
                    assertEllipsizedTextSettles(expected)
                }
            } finally {
                cancel()
            }
        }
    }

    test("style changes match Text with the same modifier and settle again") {
        var style by mutableStateOf(EllipsizedTextTestStyle())
        val snapshots = SnapshotStrategy { mosaic ->
            val canvas = mosaic.draw()
            canvas.width to List(canvas.height) { row ->
                buildString {
                    canvas.appendRowTo(this, row, AnsiLevel.TRUECOLOR, supportsKittyUnderlines = true)
                }
            }
        }

        runMosaicTest(snapshotStrategy = snapshots) {
            try {
                val initial = setContentAndSnapshot {
                    val modifier = Modifier.width(8).padding(horizontal = 1)
                    Column {
                        EllipsizedText(
                            "A你BCDEF", modifier, style.color, style.background,
                            style.textStyle, style.underlineStyle, style.underlineColor,
                        )
                        Text(
                            "A你...", modifier, style.color, style.background,
                            style.textStyle, style.underlineStyle, style.underlineColor,
                        )
                    }
                }
                assertEquals(8 to listOf(" A你...", " A你..."), initial)
                assertEllipsizedTextSettles(initial)

                val changes = listOf<(EllipsizedTextTestStyle) -> EllipsizedTextTestStyle>(
                    { it.copy(color = Color.Red) },
                    { it.copy(background = Color.Blue) },
                    { it.copy(textStyle = TextStyle.Bold + TextStyle.Italic) },
                    { it.copy(underlineStyle = UnderlineStyle.Curly) },
                    { it.copy(underlineColor = Color.Green) },
                    { EllipsizedTextTestStyle() },
                )
                var previous = initial
                changes.forEach { changeStyle ->
                    style = changeStyle(style)
                    val rendered = awaitSnapshot()
                    assertEquals(8, rendered.first)
                    assertEquals(2, rendered.second.size)
                    assertEquals(rendered.second[1], rendered.second[0])
                    assertNotEquals(previous, rendered)
                    assertEllipsizedTextSettles(rendered)
                    previous = rendered
                }
                assertEquals(initial, previous)
            } finally {
                cancel()
            }
        }
    }

    test("keeps trailing content after a wide unicode title across resize") {
        var width by mutableIntStateOf(20)

        runMosaicTest {
            assertEquals(
                "A你BCDEF +1.5s",
                setContentAndSnapshot {
                    Box(Modifier.width(width)) {
                        EllipsizedTextWithTrailingContent("A你BCDEF") {
                            Text(" +1.5s")
                        }
                    }
                },
            )

            width = 13
            assertEquals("A你B... +1.5s", awaitSnapshot())

            width = 8
            assertEquals("A +1.5s", awaitSnapshot())
        }
    }
}

private data class EllipsizedTextTestStyle(
    val color: Color = Color.Unspecified,
    val background: Color = Color.Unspecified,
    val textStyle: TextStyle = TextStyle.Unspecified,
    val underlineStyle: UnderlineStyle = UnderlineStyle.Unspecified,
    val underlineColor: Color = Color.Unspecified,
)

private suspend fun <T> TestMosaic<T>.assertEllipsizedTextSettles(expected: T) {
    // Allow up to three follow-up draws, then require 100 ms of driven frames with no new draw.
    // Repeated identical snapshots still fail: unchanged pixels do not imply an idle renderer.
    repeat(4) {
        val changed = withTimeoutOrNull(100.milliseconds) {
            assertEquals(expected, awaitSnapshot())
            true
        }
        if (changed == null) return
    }
    error("EllipsizedText kept redrawing without a state change.")
}
