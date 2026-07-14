package io.github.stream29.codex.lite.utils.applypatch

import de.infix.testBalloon.framework.core.testSuite

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith



val patchParserRustTest by testSuite {
    test("parses basic patch shapes") {
        assertFailsWith<ApplyPatchException> {
            "bad".parsePatch()
        }
        assertFailsWith<ApplyPatchException> {
            "*** Begin Patch\nbad".parsePatch()
        }

        assertEquals(
            listOf(AddFileHunk("foo", "hi\n")),
            "*** Begin Patch \n*** Add File: foo\n+hi\n *** End Patch".parsePatch().hunks,
        )

        assertFailsWith<ApplyPatchException> {
            """
                *** Begin Patch
                *** Update File: test.py
                *** End Patch
                """.trimIndent().parsePatch()
        }

        assertEquals(
            emptyList(),
            """
                *** Begin Patch
                *** End Patch
                """.trimIndent().parsePatch().hunks,
        )

        assertEquals(
            listOf(
                AddFileHunk("path/add.py", "abc\ndef\n"),
                DeleteFileHunk("path/delete.py"),
                UpdateFileHunk(
                    path = "path/update.py",
                    movePath = "path/update2.py",
                    chunks = listOf(
                        UpdateFileChunk(
                            changeContext = "def f():",
                            oldLines = listOf("    pass"),
                            newLines = listOf("    return 123"),
                        ),
                    ),
                ),
            ),
            """
                *** Begin Patch
                *** Add File: path/add.py
                +abc
                +def
                *** Delete File: path/delete.py
                *** Update File: path/update.py
                *** Move to: path/update2.py
                @@ def f():
                -    pass
                +    return 123
                *** End Patch
                """.trimIndent().parsePatch().hunks,
        )
    }

    test("parses update followed by another hunk") {
        assertEquals(
            listOf(
                UpdateFileHunk(
                    path = "file.py",
                    chunks = listOf(
                        UpdateFileChunk(
                            oldLines = emptyList(),
                            newLines = listOf("line"),
                        ),
                    ),
                ),
                AddFileHunk("other.py", "content\n"),
            ),
            """
                *** Begin Patch
                *** Update File: file.py
                @@
                +line
                *** Add File: other.py
                +content
                *** End Patch
                """.trimIndent().parsePatch().hunks,
        )
    }

    test("parses update without explicit context header") {
        assertEquals(
            listOf(
                UpdateFileHunk(
                    path = "file2.py",
                    chunks = listOf(
                        UpdateFileChunk(
                            oldLines = listOf("import foo"),
                            newLines = listOf("import foo", "bar"),
                        ),
                    ),
                ),
            ),
            """
                *** Begin Patch
                *** Update File: file2.py
                 import foo
                +bar
                *** End Patch
                """.trimIndent().parsePatch().hunks,
        )
    }

    test("preserves end of file marker") {
        val patch = "*** Begin Patch\n*** Update File: file.txt\n@@\n+quux\n*** End of File\n\n*** End Patch"
        assertEquals(
            Patch(
                patch = patch,
                hunks = listOf(
                    UpdateFileHunk(
                        path = "file.txt",
                        chunks = listOf(
                            UpdateFileChunk(
                                oldLines = emptyList(),
                                newLines = listOf("quux"),
                                isEndOfFile = true,
                            ),
                        ),
                    ),
                ),
            ),
            patch.parsePatch(),
        )
    }

    test("accepts relative and absolute hunk paths") {
        val patch = """
            *** Begin Patch
            *** Add File: relative-add.py
            +content
            *** Delete File: /tmp/absolute-delete.py
            *** Update File: /tmp/absolute-update.py
            @@
            -old
            +new
            *** End Patch
        """.trimIndent()

        assertEquals(
            listOf(
                AddFileHunk("relative-add.py", "content\n"),
                DeleteFileHunk("/tmp/absolute-delete.py"),
                UpdateFileHunk(
                    path = "/tmp/absolute-update.py",
                    chunks = listOf(
                        UpdateFileChunk(
                            oldLines = listOf("old"),
                            newLines = listOf("new"),
                        ),
                    ),
                ),
            ),
            patch.parsePatch().hunks,
        )
    }

    test("parses lenient heredoc wrappers") {
        val patch = """
            *** Begin Patch
            *** Update File: file2.py
             import foo
            +bar
            *** End Patch
        """.trimIndent()
        val expected = listOf(
            UpdateFileHunk(
                path = "file2.py",
                chunks = listOf(
                    UpdateFileChunk(
                        oldLines = listOf("import foo"),
                        newLines = listOf("import foo", "bar"),
                    ),
                ),
            ),
        )

        assertEquals(expected, "<<EOF\n$patch\nEOF\n".parsePatch().hunks)
        assertEquals(expected, "<<'EOF'\n$patch\nEOF\n".parsePatch().hunks)
        assertEquals(expected, "<<\"EOF\"\n$patch\nEOF\n".parsePatch().hunks)
        assertFailsWith<ApplyPatchException> {
            "<<\"EOF'\n$patch\nEOF\n".parsePatch()
        }
        assertFailsWith<ApplyPatchException> {
            "<<EOF\n*** Begin Patch\n*** Update File: file2.py\nEOF\n".parsePatch()
        }
    }

    test("parses environment id preamble") {
        val parsed = """
            *** Begin Patch
            *** Environment ID: remote
            *** Add File: hello.txt
            +hello
            *** End Patch
            """.trimIndent().parsePatch()

        assertEquals("remote", parsed.environmentId)
        assertEquals(listOf(AddFileHunk("hello.txt", "hello\n")), parsed.hunks)
        assertFailsWith<ApplyPatchException> {
            """
                *** Begin Patch
                *** Environment ID:   
                *** Add File: hello.txt
                +hello
                *** End Patch
                """.trimIndent().parsePatch()
        }
    }
}
