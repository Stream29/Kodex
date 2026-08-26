package io.github.stream29.kodex.cli.patch

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolExecutionResult
import io.github.stream29.kodex.utils.applypatch.Patch
import io.github.stream29.kodex.utils.applypatch.PatchAffectedPaths
import io.github.stream29.kodex.utils.applypatch.PatchApplyResult
import io.github.stream29.kodex.utils.applypatch.PatchChange
import io.github.stream29.kodex.utils.applypatch.PatchDelta
import io.github.stream29.kodex.utils.applypatch.PatchFileChange
import io.github.stream29.kodex.utils.applypatch.UpdateFileChunk
import io.github.stream29.kodex.utils.applypatch.UpdateFileHunk
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val patchPresentationTest by testSuite {
    test("projects an update hunk into context removal and addition lines") {
        val presentation = updatePatch().toPendingPatchPresentation()

        assertEquals("Editing Main.kt", presentation.header)
        assertEquals(PatchPresentationTarget.SingleFile("Main.kt"), presentation.target)
        assertEquals(PatchPresentationStatus.Running, presentation.status)
        assertEquals(
            listOf(
                "M src/Main.kt",
                "@@ render",
                "  before",
                "- old",
                "+ new",
                "  after",
            ),
            presentation.lines.map(PatchPresentationLine::text),
        )
    }

    test("keeps unchanged lines between separate edits as context") {
        val presentation = Patch(
            patch = "",
            hunks = listOf(
                UpdateFileHunk(
                    path = "src/Main.kt",
                    chunks = listOf(
                        UpdateFileChunk(
                            oldLines = listOf("before", "old one", "middle", "old two", "after"),
                            newLines = listOf("before", "new one", "middle", "new two", "after"),
                        ),
                    ),
                ),
            ),
        ).toPendingPatchPresentation()

        assertEquals(
            listOf(
                "M src/Main.kt",
                "@@",
                "  before",
                "- old one",
                "+ new one",
                "  middle",
                "- old two",
                "+ new two",
                "  after",
            ),
            presentation.lines.map(PatchPresentationLine::text),
        )
    }

    test("completed failure retains the structured diff and failure reason") {
        val presentation = StablePatchToolEvent(
            callId = "patch",
            diff = updatePatch(),
            result = StablePatchToolExecutionResult.Failure("context did not match"),
        ).toStablePatchPresentation()

        assertEquals("Failed to edit Main.kt", presentation.header)
        assertEquals(PatchPresentationStatus.Failed, presentation.status)
        assertTrue(
            PatchPresentationLine(
                text = "Error: context did not match",
                kind = PatchPresentationLineKind.Failure,
            ) in presentation.lines,
        )
        assertTrue(presentation.lines.any { it.text == "M src/Main.kt" })
    }

    test("completed success renders the exact applied delta instead of the input hunks") {
        val presentation = StablePatchToolEvent(
            callId = "patch",
            diff = updatePatch(),
            result = StablePatchToolExecutionResult.Success(
                PatchApplyResult(
                    affectedPaths = PatchAffectedPaths(
                        added = emptyList(),
                        modified = listOf("applied.txt"),
                        deleted = emptyList(),
                    ),
                    delta = PatchDelta(
                        changes = listOf(
                            PatchChange(
                                path = "applied.txt",
                                change = PatchFileChange.Update(
                                    movePath = null,
                                    oldContent = "before\nold\nmiddle\nold two\nafter\n",
                                    overwrittenMoveContent = null,
                                    newContent = "before\nnew\nmiddle\nnew two\nafter\n",
                                ),
                            ),
                        ),
                        exact = true,
                    ),
                ),
            ),
        ).toStablePatchPresentation()

        assertEquals("Edit applied.txt", presentation.header)
        assertEquals(PatchPresentationStatus.Completed, presentation.status)
        assertTrue(presentation.lines.any { it.text == "M applied.txt" })
        assertTrue(presentation.lines.any { it.text == "- old" })
        assertTrue(presentation.lines.any { it.text == "+ new" })
        assertTrue(presentation.lines.any { it.text == "  middle" })
        assertFalse(presentation.lines.any { it.text == "M src/Main.kt" })
    }

    test("completed success renders add delete rename and overwritten destinations") {
        val presentation = StablePatchToolEvent(
            callId = "patch",
            diff = Patch(patch = "", hunks = emptyList()),
            result = StablePatchToolExecutionResult.Success(
                PatchApplyResult(
                    affectedPaths = PatchAffectedPaths(
                        added = listOf("new.txt", "replaced.txt"),
                        modified = listOf("moved.txt"),
                        deleted = listOf("old.txt"),
                    ),
                    delta = PatchDelta(
                        changes = listOf(
                            PatchChange(
                                path = "new.txt",
                                change = PatchFileChange.Add(
                                    content = "new\n",
                                    overwrittenContent = null,
                                ),
                            ),
                            PatchChange(
                                path = "replaced.txt",
                                change = PatchFileChange.Add(
                                    content = "replacement\n",
                                    overwrittenContent = "original\n",
                                ),
                            ),
                            PatchChange(
                                path = "old.txt",
                                change = PatchFileChange.Delete("deleted\n"),
                            ),
                            PatchChange(
                                path = "source.txt",
                                change = PatchFileChange.Update(
                                    movePath = "moved.txt",
                                    oldContent = "old\n",
                                    overwrittenMoveContent = "destination\n",
                                    newContent = "new\n",
                                ),
                            ),
                        ),
                        exact = true,
                    ),
                ),
            ),
        ).toStablePatchPresentation()

        assertEquals("Edit 4 files", presentation.header)
        assertEquals(PatchPresentationStatus.Completed, presentation.status)
        val renderedLines = presentation.lines.map(PatchPresentationLine::text)
        assertTrue("A new.txt" in renderedLines)
        assertTrue("+ new" in renderedLines)
        assertTrue("M replaced.txt (replaced by add)" in renderedLines)
        assertTrue("- original" in renderedLines)
        assertTrue("+ replacement" in renderedLines)
        assertTrue("D old.txt" in renderedLines)
        assertTrue("- deleted" in renderedLines)
        assertTrue("R source.txt -> moved.txt" in renderedLines)
        assertTrue("Overwrote existing destination: moved.txt" in renderedLines)
    }
}

private fun updatePatch(): Patch =
    Patch(
        patch = "",
        hunks = listOf(
            UpdateFileHunk(
                path = "src/Main.kt",
                chunks = listOf(
                    UpdateFileChunk(
                        changeContext = "render",
                        oldLines = listOf("before", "old", "after"),
                        newLines = listOf("before", "new", "after"),
                    ),
                ),
            ),
        ),
    )
