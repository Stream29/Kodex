package io.github.stream29.kodex.utils.applypatch

import de.infix.testBalloon.framework.core.testSuite

import kotlin.test.assertEquals
import kotlin.test.assertIs

val patchParserStrictTest by testSuite {
    test("preserves exact content across mixed hunks") {
        val patch = listOf(
            "*** Begin Patch",
            "*** Environment ID: remote",
            "*** Add File: add.txt",
            "+first",
            "+",
            "+third  ",
            "*** Update File: old.txt",
            "*** Move to: moved.txt",
            "@@ first anchor  ",
            " context before  ",
            "-old  ",
            "+new  ",
            "@@ second anchor",
            "-old second",
            "+new second",
            "*** End of File",
            "*** Delete File: delete.txt",
            "*** Add File: tail.txt",
            "++starts with plus",
            "+-starts with minus",
            "+*** Update File: content.txt",
            "*** End Patch",
        ).joinToString("\n")

        assertEquals(
            Patch(
                patch = patch,
                hunks = listOf(
                    AddFileHunk(
                        path = "add.txt",
                        contents = "first\n\nthird  \n",
                    ),
                    UpdateFileHunk(
                        path = "old.txt",
                        movePath = "moved.txt",
                        chunks = listOf(
                            UpdateFileChunk(
                                changeContext = "first anchor",
                                oldLines = listOf("context before  ", "old  "),
                                newLines = listOf("context before  ", "new  "),
                            ),
                            UpdateFileChunk(
                                changeContext = "second anchor",
                                oldLines = listOf("old second"),
                                newLines = listOf("new second"),
                                isEndOfFile = true,
                            ),
                        ),
                    ),
                    DeleteFileHunk(path = "delete.txt"),
                    AddFileHunk(
                        path = "tail.txt",
                        contents = "+starts with plus\n-starts with minus\n*** Update File: content.txt\n",
                    ),
                ),
                environmentId = "remote",
            ),
            patch.parsePatch(),
        )
    }

    test("keeps implicit and explicit update chunks independent") {
        val patch = listOf(
            "*** Begin Patch",
            "*** Update File: file.txt",
            " implicit context",
            "-old implicit",
            "+new implicit",
            "@@ named context",
            "+",
            "-",
            " ",
            "*** End Patch",
        ).joinToString("\n")

        assertEquals(
            listOf(
                UpdateFileHunk(
                    path = "file.txt",
                    chunks = listOf(
                        UpdateFileChunk(
                            oldLines = listOf("implicit context", "old implicit"),
                            newLines = listOf("implicit context", "new implicit"),
                        ),
                        UpdateFileChunk(
                            changeContext = "named context",
                            oldLines = listOf("", ""),
                            newLines = listOf("", ""),
                        ),
                    ),
                ),
            ),
            patch.parsePatch().hunks,
        )
    }

    test("retains every line in larger add and update hunks") {
        val addLines = List(257) { index -> "add-$index" }
        val oldLines = List(257) { index -> "old-$index" }
        val newLines = List(257) { index -> "new-$index" }
        val patch = buildString {
            appendLine("*** Begin Patch")
            appendLine("*** Add File: add.txt")
            addLines.forEach { line -> appendLine("+$line") }
            appendLine("*** Update File: update.txt")
            appendLine("@@")
            oldLines.zip(newLines).forEach { (oldLine, newLine) ->
                appendLine("-$oldLine")
                appendLine("+$newLine")
            }
            append("*** End Patch")
        }

        val hunks = patch.parsePatch().hunks
        assertEquals(2, hunks.size)
        assertEquals(
            addLines.joinToString(separator = "\n", postfix = "\n"),
            assertIs<AddFileHunk>(hunks[0]).contents,
        )
        val update = assertIs<UpdateFileHunk>(hunks[1])
        assertEquals(1, update.chunks.size)
        assertEquals(oldLines, update.chunks.single().oldLines)
        assertEquals(newLines, update.chunks.single().newLines)
    }
}
