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
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val patchToolEventViewTest by testSuite {
    test("pendingPatchCanExpandItsStructuredDiff") {
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
            assertEquals("> Editing file.txt", collapsed)

            val expanded = click()
            assertTrue("\napply_patch\n" in expanded)
            assertTrue("Changes" !in expanded)
            assertTrue("M file.txt" in expanded)
            assertTrue("- old" in expanded)
            assertTrue("+ new" in expanded)
        }
    }

    test("narrowPatchIndentsWrappedContinuationLines") {
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
            assertEquals(
                listOf(
                    "v Editing ver...",
                    "apply_patch",
                    "A src/very-long-",
                    "  name.kt",
                    "+ 界界界界界界ab",
                    "  cdefghi",
                ).joinToString("\n"),
                click(),
            )
        }
    }

    test("oneCellPatchUsesAPlaceholderForAnUnrepresentableWideGrapheme") {
        assertEquals(listOf("?"), "界".wrapPatchHardLine(width = 1, continuationPrefix = "  "))
    }

    test("diffBodyKeepsSemanticAnsiColors") {
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
            val rendered = click()
                .draw()
                .render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)

            assertTrue("\u001B[38;2;255;0;0m- old" in rendered)
            assertTrue("\u001B[38;2;0;255;0m+ new" in rendered)
            assertTrue(
                rendered.indexOf("38;2;0;255;0") < rendered.indexOf("v Editing file.txt"),
            )
        }
    }

    test("failedPatchUsesARedToolHeaderWithoutStatusText") {
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

    test("firstExpansionKeepsMetadataFailureAndMoveContext") {
        val event = StablePatchToolEvent(
            callId = "patch",
            diff = Patch(
                patch = "",
                workdir = "/workspace",
                environmentId = "env-1",
                hunks = listOf(UpdateFileHunk(
                    path = "old.kt",
                    movePath = "new.kt",
                    chunks = listOf(UpdateFileChunk(oldLines = listOf("old"), newLines = listOf("new"))),
                )),
            ),
            result = StablePatchToolExecutionResult.Failure("context did not match"),
        )
        runMosaicTest {
            val text = setContentAndSnapshot {
                Box(Modifier.width(80)) {
                    StablePatchToolEventView(event, expanded = true, onToggleExpanded = {})
                }
            }
            assertTrue("Working directory: /workspace" in text)
            assertTrue("Environment: env-1" in text)
            assertTrue("Error: context did not match" in text)
            assertTrue("R old.kt -> new.kt" in text)
            assertTrue("- old" in text)
            assertTrue("+ new" in text)
            assertTrue("Changes" !in text)
        }
    }

    test("stablePatchKeepsElapsedAtTheEndOfItsHeader") {
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
                "> Failed to edit file.txt +1.5s",
                setContentAndSnapshot {
                    Box(Modifier.width(40)) {
                        StablePatchToolEventView(
                            event = event,
                            headerTrailingText = " +1.5s",
                        )
                    }
                },
            )

            assertEquals(
                "> Fai... +1.5s",
                setContentAndSnapshot {
                    Box(Modifier.width(14)) {
                        StablePatchToolEventView(
                            event = event,
                            headerTrailingText = " +1.5s",
                        )
                    }
                },
            )
        }
    }

    test("largePatchUsesABoundedNumberOfComposedTextNodes") {
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
            val mosaic = click()
            val rendered = mosaic.draw().render(
                ansiLevel = AnsiLevel.NONE,
                supportsKittyUnderlines = false,
            )
            assertEquals(203, rendered.lineSequence().count())
            assertTrue("1801 remaining" in rendered)

            val nodeDump = mosaic.dumpNodes()
            val textNodeCount = nodeDump.lineSequence().count { line ->
                "Text(\"" in line
            }

            assertTrue(
                actual = textNodeCount <= 5,
                message = "Expected at most five Text nodes, found $textNodeCount",
            )

            val afterSecondClick = click(x = 1, y = 202).draw().render(
                ansiLevel = AnsiLevel.NONE,
                supportsKittyUnderlines = false,
            )
            assertEquals(403, afterSecondClick.lineSequence().count())
            assertTrue("1601 remaining" in afterSecondClick)
        }
    }
}

private suspend fun <T> TestMosaic<T>.click(
    x: Int = 0,
    y: Int = 0,
): T {
    sendMouseEvent(MouseEvent(x, y, MouseEvent.Type.Motion))
    awaitSnapshot()
    sendMouseEvent(MouseEvent(x, y, MouseEvent.Type.Press, MouseEvent.Button.Left))
    awaitSnapshot()
    sendMouseEvent(MouseEvent(x, y, MouseEvent.Type.Release))
    return awaitSnapshot()
}
