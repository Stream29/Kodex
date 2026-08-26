package io.github.stream29.kodex.utils.applypatch

import de.infix.testBalloon.framework.core.testSuite

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val patchParserEdgeCaseRustTest by testSuite {
    test("keeps indented update markers as context lines") {
        val patch = """
            *** Begin Patch
            *** Update File: a.txt
            @@
            -old a
            +new a
             *** Update File: b.txt
            @@
            -old b
            +new b
            *** End Patch
        """.trimIndent()

        assertEquals(
            listOf(
                UpdateFileHunk(
                    path = "a.txt",
                    chunks = listOf(
                        UpdateFileChunk(
                            oldLines = listOf("old a", "*** Update File: b.txt"),
                            newLines = listOf("new a", "*** Update File: b.txt"),
                        ),
                        UpdateFileChunk(
                            oldLines = listOf("old b"),
                            newLines = listOf("new b"),
                        ),
                    ),
                ),
            ),
            patch.parsePatch().hunks,
        )
    }

    test("preserves bare empty update lines") {
        val patch = """
            *** Begin Patch
            *** Update File: file.txt
            @@
             context before

             context after
            *** End Patch
        """.trimIndent()

        assertEquals(
            listOf(
                UpdateFileHunk(
                    path = "file.txt",
                    chunks = listOf(
                        UpdateFileChunk(
                            oldLines = listOf("context before", "", "context after"),
                            newLines = listOf("context before", "", "context after"),
                        ),
                    ),
                ),
            ),
            patch.parsePatch().hunks,
        )
    }

    test("normalizes CRLF line endings") {
        assertEquals(
            listOf(
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
            (
                "*** Begin Patch\r\n" +
                    "*** Update File: file.txt\r\n" +
                    "@@\r\n" +
                    "-old\r\n" +
                    "+new\r\n" +
                    "*** End Patch\r\n"
                ).parsePatch().hunks,
        )
    }

    test("rejects duplicate environment ids") {
        val error = assertFailsWith<ApplyPatchException> {
            """
                *** Begin Patch
                *** Environment ID: first
                *** Environment ID: second
                *** End Patch
            """.trimIndent().parsePatch()
        }
        assertEquals(
            "apply_patch environment_id cannot be specified more than once",
            error.message,
        )
    }

    test("reports exact errors for invalid patch body lines") {
        listOf(
            InvalidPatchCase(
                name = "invalid hunk header",
                patch = """
                    *** Begin Patch
                    bad
                    *** End Patch
                """,
                expectedMessage = "Invalid patch hunk at line 2, 'bad' is not a valid hunk header",
            ),
            InvalidPatchCase(
                name = "invalid add line",
                patch = """
                    *** Begin Patch
                    *** Add File: file.txt
                    bad
                    *** End Patch
                """,
                expectedMessage = "Invalid patch hunk at line 3, 'bad' is not a valid hunk header",
            ),
            InvalidPatchCase(
                name = "invalid delete body",
                patch = """
                    *** Begin Patch
                    *** Delete File: file.txt
                    bad
                    *** End Patch
                """,
                expectedMessage = "Invalid patch hunk at line 3, 'bad' is not a valid hunk header",
            ),
            InvalidPatchCase(
                name = "empty update",
                patch = """
                    *** Begin Patch
                    *** Update File: file.txt
                    *** End Patch
                """,
                expectedMessage = "Invalid patch hunk at line 3, Update file hunk for path 'file.txt' is empty",
            ),
            InvalidPatchCase(
                name = "move-only update",
                patch = """
                    *** Begin Patch
                    *** Update File: old.txt
                    *** Move to: new.txt
                    *** Delete File: other.txt
                    *** End Patch
                """,
                expectedMessage = "Invalid patch hunk at line 4, Update file hunk for path 'old.txt' is empty",
            ),
            InvalidPatchCase(
                name = "empty update chunk",
                patch = """
                    *** Begin Patch
                    *** Update File: file.txt
                    @@
                    *** End Patch
                """,
                expectedMessage = "Invalid patch hunk at line 4, Update hunk does not contain any lines",
            ),
            InvalidPatchCase(
                name = "end marker after empty chunk",
                patch = """
                    *** Begin Patch
                    *** Update File: file.txt
                    @@
                    *** End of File
                    *** End Patch
                """,
                expectedMessage = "Invalid patch hunk at line 4, Update hunk does not contain any lines",
            ),
            InvalidPatchCase(
                name = "consecutive context markers",
                patch = """
                    *** Begin Patch
                    *** Update File: file.txt
                    @@
                    @@
                    *** End Patch
                """,
                expectedMessage = "Invalid patch hunk at line 4, Unexpected line found in update hunk: '@@'",
            ),
            InvalidPatchCase(
                name = "unmarked line after update content",
                patch = """
                    *** Begin Patch
                    *** Update File: file.txt
                    @@
                    -old
                    bad
                    *** End Patch
                """,
                expectedMessage = "Invalid patch hunk at line 5, " +
                    "Expected update hunk to start with a @@ context marker, got: 'bad'",
            ),
            InvalidPatchCase(
                name = "content after end marker",
                patch = """
                    *** Begin Patch
                    *** End Patch
                    extra
                    *** End Patch
                """,
                expectedMessage = "The last line of the patch must be '*** End Patch'",
            ),
        ).forEach { case ->
            val error = assertFailsWith<ApplyPatchException> {
                case.patch.trimIndent().parsePatch()
            }
            assertEquals(case.expectedMessage, error.message, case.name)
        }
    }
}

private data class InvalidPatchCase(
    val name: String,
    val patch: String,
    val expectedMessage: String,
)
