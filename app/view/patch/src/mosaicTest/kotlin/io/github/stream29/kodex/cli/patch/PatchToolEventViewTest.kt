package io.github.stream29.kodex.cli.patch

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolExecutionResult
import io.github.stream29.kodex.utils.applypatch.AddFileHunk
import io.github.stream29.kodex.utils.applypatch.Patch
import io.github.stream29.kodex.utils.applypatch.UpdateFileChunk
import io.github.stream29.kodex.utils.applypatch.UpdateFileHunk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PatchToolEventViewTest {
    @Test
    fun pendingPatchCanExpandItsStructuredDiff() = runTest {
        val patch = Patch(
            patch = "",
            hunks = listOf(
                UpdateFileHunk(
                    path = "file.txt",
                    chunks = listOf(
                        UpdateFileChunk(
                            oldLines = listOf("old"),
                            newLines = listOf("new"),
                        ),
                    ),
                ),
            ),
        )

        runMosaicTest {
            val collapsed = setContentAndSnapshot {
                Box(Modifier.width(40)) {
                    PendingPatchToolEventView(patch)
                }
            }
            assertEquals("> Editing 1 file", collapsed)

            val expanded = click()
            assertTrue("Tool: apply_patch" in expanded)
            assertTrue("> Changes" in expanded)
            assertTrue("M file.txt" !in expanded)

            val changes = click(y = 2)
            assertTrue("M file.txt" in changes)
            assertTrue("- old" in changes)
            assertTrue("+ new" in changes)
        }
    }

    @Test
    fun narrowPatchIndentsWrappedContinuationLines() = runTest {
        val patch = Patch(
            patch = "",
            hunks = listOf(
                AddFileHunk(
                    path = "src/very-long-name.kt",
                    contents = "界界界界界界abcdefghi\n",
                ),
            ),
        )

        runMosaicTest {
            setContentAndSnapshot {
                Box(Modifier.width(16)) {
                    PendingPatchToolEventView(patch)
                }
            }
            click()
            assertEquals(
                listOf(
                    "v Editing 1 file",
                    "Tool: apply_patc",
                    "  h",
                    "v Changes",
                    "A src/very-long-",
                    "  name.kt",
                    "+ 界界界界界界ab",
                    "  cdefghi",
                ).joinToString("\n"),
                click(y = 3),
            )
        }
    }

    @Test
    fun oneCellPatchUsesAPlaceholderForAnUnrepresentableWideGrapheme() {
        assertEquals(listOf("?"), "界".wrapPatchHardLine(width = 1, continuationPrefix = "  "))
    }

    @Test
    fun diffBodyKeepsSemanticAnsiColors() = runTest {
        val patch = Patch(
            patch = "",
            hunks = listOf(
                UpdateFileHunk(
                    path = "file.txt",
                    chunks = listOf(
                        UpdateFileChunk(
                            oldLines = listOf("old"),
                            newLines = listOf("new"),
                        ),
                    ),
                ),
            ),
        )

        runMosaicTest(MosaicSnapshots) {
            setContentAndSnapshot {
                Box(Modifier.width(40)) {
                    PendingPatchToolEventView(patch)
                }
            }
            click()
            val rendered = click(y = 2)
                .draw()
                .render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)

            assertTrue("\u001B[38;2;255;0;0m- old" in rendered)
            assertTrue("\u001B[38;2;0;255;0m+ new" in rendered)
            assertTrue(
                rendered.indexOf("38;2;0;255;0") < rendered.indexOf("v Editing 1 file"),
            )
        }
    }

    @Test
    fun failedPatchUsesARedToolHeaderWithoutStatusText() = runTest {
        val event = StablePatchToolEvent(
            callId = "patch",
            diff = Patch(
                patch = "",
                hunks = listOf(
                    UpdateFileHunk(
                        path = "file.txt",
                        chunks = emptyList(),
                    ),
                ),
            ),
            result = StablePatchToolExecutionResult.Failure("patch did not apply"),
        )

        runMosaicTest(MosaicSnapshots) {
            val rendered = setContentAndSnapshot {
                Box(Modifier.width(40)) {
                    StablePatchToolEventView(event)
                }
            }.draw().render(
                ansiLevel = AnsiLevel.TRUECOLOR,
                supportsKittyUnderlines = false,
            )

            assertTrue("38;2;255;0;0" in rendered)
            assertTrue("failed" !in rendered)
        }
    }

    @Test
    fun stablePatchKeepsElapsedAtTheEndOfItsHeader() = runTest {
        val event = StablePatchToolEvent(
            callId = "patch",
            diff = Patch(
                patch = "",
                hunks = listOf(
                    UpdateFileHunk(
                        path = "file.txt",
                        chunks = emptyList(),
                    ),
                ),
            ),
            result = StablePatchToolExecutionResult.Failure("not applied"),
        )

        runMosaicTest {
            assertEquals(
                "> Editing 1 file · +1.5s",
                setContentAndSnapshot {
                    Box(Modifier.width(40)) {
                        StablePatchToolEventView(
                            event = event,
                            headerTrailingText = " · +1.5s",
                        )
                    }
                },
            )

            assertEquals(
                "> E... · +1.5s",
                setContentAndSnapshot {
                    Box(Modifier.width(14)) {
                        StablePatchToolEventView(
                            event = event,
                            headerTrailingText = " · +1.5s",
                        )
                    }
                },
            )
        }
    }

    @Test
    fun largePatchUsesABoundedNumberOfComposedTextNodes() = runTest {
        val patch = Patch(
            patch = "",
            hunks = listOf(
                AddFileHunk(
                    path = "large.txt",
                    contents = (1..2_000).joinToString(
                        separator = "\n",
                        postfix = "\n",
                    ) { index ->
                        "line $index"
                    },
                ),
            ),
        )

        runMosaicTest(MosaicSnapshots) {
            setContentAndSnapshot {
                Box(Modifier.width(80)) {
                    PendingPatchToolEventView(patch)
                }
            }
            click()
            val mosaic = click(y = 2)
            val rendered = mosaic.draw().render(
                ansiLevel = AnsiLevel.NONE,
                supportsKittyUnderlines = false,
            )
            assertEquals(204, rendered.lineSequence().count())
            assertTrue("1801 remaining" in rendered)

            val nodeDump = mosaic.dumpNodes()
            val textNodeCount = nodeDump.lineSequence().count { line ->
                "Text(\"" in line
            }

            assertTrue(
                actual = textNodeCount <= 5,
                message = "Expected at most five Text nodes, found $textNodeCount",
            )

            val afterSecondClick = click(x = 1, y = 203).draw().render(
                ansiLevel = AnsiLevel.NONE,
                supportsKittyUnderlines = false,
            )
            assertEquals(404, afterSecondClick.lineSequence().count())
            assertTrue("1601 remaining" in afterSecondClick)
        }
    }
}

private suspend fun <T> TestMosaic<T>.click(
    x: Int = 0,
    y: Int = 0,
): T {
    sendMouseEvent(MouseEvent(x, y, MouseEvent.Type.Press, MouseEvent.Button.Left))
    awaitSnapshot()
    sendMouseEvent(MouseEvent(x, y, MouseEvent.Type.Release))
    return awaitSnapshot()
}
