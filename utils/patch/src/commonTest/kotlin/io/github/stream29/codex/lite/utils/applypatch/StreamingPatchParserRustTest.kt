package io.github.stream29.codex.lite.utils.applypatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IncrementalPatchParserRustTest {
    @Test
    fun streamsCompleteLinesBeforeEndPatch() {
        IncrementalPatchParser().let { parser ->
            assertEquals(
                listOf(AddFileHunk("src/hello.txt", "hello\n")),
                parser.pushDelta("*** Begin Patch\n*** Add File: src/hello.txt\n+hello\n+wor"),
            )
            assertEquals(
                listOf(AddFileHunk("src/hello.txt", "hello\nworld\n")),
                parser.pushDelta("ld\n"),
            )
        }

        IncrementalPatchParser().let { parser ->
            assertEquals(
                listOf(
                    UpdateFileHunk(
                        path = "src/old.rs",
                        movePath = "src/new.rs",
                        chunks = listOf(
                            UpdateFileChunk(
                                oldLines = listOf("old"),
                                newLines = listOf("new"),
                            ),
                        ),
                    ),
                ),
                parser.pushDelta(
                    "*** Begin Patch\n*** Update File: src/old.rs\n*** Move to: src/new.rs\n@@\n-old\n+new\n",
                ),
            )
        }

        IncrementalPatchParser().let { parser ->
            assertEquals(emptyList(), parser.pushDelta("*** Begin Patch\n*** Delete File: gone.txt"))
            assertEquals(listOf(DeleteFileHunk("gone.txt")), parser.pushDelta("\n"))
        }
    }

    @Test
    fun parsesEnvironmentIdMode() {
        val parser = IncrementalPatchParser()
        assertEquals(
            listOf(AddFileHunk("src/hello.txt", "hello\n")),
            parser.pushDelta(
                """
                *** Begin Patch
                *** Environment ID: remote
                *** Add File: src/hello.txt
                +hello
                *** End Patch
                
                """.trimIndent(),
            ),
        )
        assertEquals("remote", parser.environmentId)

        assertFailsWith<ApplyPatchException> {
            IncrementalPatchParser().pushDelta(
                "*** Begin Patch\n*** Environment ID: first\n*** Environment ID: second\n",
            )
        }
        assertFailsWith<ApplyPatchException> {
            IncrementalPatchParser().pushDelta("*** Begin Patch\n*** Environment ID:   \n")
        }
    }

    @Test
    fun largePatchSplitByCharacterNeverLosesHunks() {
        val patch = """
            *** Begin Patch
            *** Add File: docs/release-notes.md
            +# Release notes
            +
            +## CLI
            +- Surface apply_patch progress while arguments stream.
            *** Update File: src/config.rs
            @@ impl Config
            -    pub apply_patch_progress: bool,
            +    pub stream_apply_patch_progress: bool,
            *** Delete File: src/legacy_patch_progress.rs
            *** Update File: crates/cli/src/main.rs
            *** Move to: crates/cli/src/bin/codex.rs
            @@ fn run()
            -    let args = Args::parse();
            +    let cli = Cli::parse();
            *** Add File: tests/fixtures/apply_patch_progress.json
            +{
            +  "type": "apply_patch_progress"
            +}
            *** Update File: README.md
            @@ Development workflow
             Build the Rust workspace before opening a pull request.
            +When touching streamed tool calls, include parser coverage for partial input.
            *** Delete File: docs/old-apply-patch-progress.md
            *** End Patch
        """.trimIndent()

        val parser = IncrementalPatchParser()
        var maxHunkCount = 0
        val sawHunkCounts = mutableListOf<Int>()
        var hunks = emptyList<Hunk>()
        patch.forEach { ch ->
            val updatedHunks = parser.pushDelta(ch.toString())
            if (updatedHunks.isNotEmpty()) {
                val hunkCount = updatedHunks.size
                assertTrue(hunkCount >= maxHunkCount)
                if (hunkCount > maxHunkCount) {
                    sawHunkCounts += hunkCount
                    maxHunkCount = hunkCount
                }
                hunks = updatedHunks
            }
        }

        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), sawHunkCounts)
        assertEquals(7, hunks.size)
        assertEquals(
            listOf("add", "update", "delete", "move-update", "add", "update", "delete"),
            hunks.map {
                when (it) {
                    is AddFileHunk -> "add"
                    is DeleteFileHunk -> "delete"
                    is UpdateFileHunk -> if (it.movePath == null) "update" else "move-update"
                }
            },
        )
    }

    @Test
    fun keepsIndentedUpdateMarkersAsContextLines() {
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
            IncrementalPatchParser().pushDelta(
                """
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
                
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun preservesBareEmptyUpdateLines() {
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
            IncrementalPatchParser().pushDelta(
                """
                *** Begin Patch
                *** Update File: file.txt
                @@
                 context before
                
                 context after
                *** End Patch
                
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun ignoresEmptyLinesAfterEndOfFile() {
        assertEquals(
            listOf(
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
            IncrementalPatchParser().pushDelta(
                "*** Begin Patch\n*** Update File: file.txt\n@@\n+quux\n*** End of File\n\n*** End Patch\n",
            ),
        )
    }

    @Test
    fun matchesLineEndingBehavior() {
        assertEquals(
            listOf(
                UpdateFileHunk(
                    path = "file.txt",
                    chunks = listOf(UpdateFileChunk(oldLines = listOf("old"), newLines = listOf("new"))),
                ),
            ),
            IncrementalPatchParser().pushDelta(
                "*** Begin Patch\r\n*** Update File: file.txt\r\n@@\r\n-old\r\n+new\r\n*** End Patch\r\n",
            ),
        )

        assertEquals(
            listOf(
                UpdateFileHunk(
                    path = "file.txt",
                    chunks = listOf(UpdateFileChunk(oldLines = listOf("old\r"), newLines = listOf("new"))),
                ),
            ),
            IncrementalPatchParser().pushDelta(
                "*** Begin Patch\r\n*** Update File: file.txt\r\n@@\r\n-old\r\r\n+new\r\n*** End Patch\r\n",
            ),
        )
    }

    @Test
    fun finishProcessesFinalLineWithoutNewline() {
        IncrementalPatchParser().let { parser ->
            assertEquals(
                listOf(AddFileHunk("file.txt", "hello\n")),
                parser.pushDelta("*** Begin Patch\n*** Add File: file.txt\n+hello\n*** End Patch"),
            )
            assertEquals(listOf(AddFileHunk("file.txt", "hello\n")), parser.finish())
        }

        IncrementalPatchParser().let { parser ->
            val expected = listOf(
                UpdateFileHunk(
                    path = "file.txt",
                    chunks = listOf(UpdateFileChunk(oldLines = listOf("old"), newLines = listOf("new"))),
                ),
            )
            assertEquals(
                expected,
                parser.pushDelta("*** Begin Patch\n*** Update File: file.txt\n@@\n-old\n+new\n *** End Patch"),
            )
            assertEquals(expected, parser.finish())
        }
    }

    @Test
    fun finishRequiresEndPatchAndRejectsContentAfterEndPatch() {
        val parser = IncrementalPatchParser()
        assertEquals(listOf(AddFileHunk("file.txt", "hello\n")), parser.pushDelta("*** Begin Patch\n*** Add File: file.txt\n+hello\n"))
        assertFailsWith<ApplyPatchException> {
            parser.finish()
        }

        assertFailsWith<ApplyPatchException> {
            IncrementalPatchParser().pushDelta("*** Begin Patch\n*** Add File: file.txt\n+hello\n*** End Patch\nextra\n")
        }

        assertEquals(
            listOf(AddFileHunk("file.txt", "hello\n")),
            IncrementalPatchParser().pushDelta("*** Begin Patch\n*** Add File: file.txt\n+hello\n*** End Patch\n \t\n"),
        )
    }

    @Test
    fun returnsErrors() {
        assertFailsWith<ApplyPatchException> {
            IncrementalPatchParser().pushDelta("bad\n")
        }
        IncrementalPatchParser().let { parser ->
            assertEquals(emptyList(), parser.pushDelta("*** Begin Patch\n"))
            assertFailsWith<ApplyPatchException> {
                parser.pushDelta("bad\n")
            }
        }
        assertFailsWith<ApplyPatchException> {
            IncrementalPatchParser().pushDelta("*** Begin Patch\n*** Add File: file.txt\nbad\n")
        }
        assertFailsWith<ApplyPatchException> {
            IncrementalPatchParser().pushDelta("*** Begin Patch\n*** Delete File: file.txt\nbad\n")
        }
        assertFailsWith<ApplyPatchException> {
            IncrementalPatchParser().pushDelta("*** Begin Patch\n*** Update File: file.txt\n*** End Patch\n")
        }
        assertFailsWith<ApplyPatchException> {
            IncrementalPatchParser().pushDelta(
                "*** Begin Patch\n*** Update File: old.txt\n*** Move to: new.txt\n*** Delete File: other.txt\n",
            )
        }
        assertFailsWith<ApplyPatchException> {
            IncrementalPatchParser().pushDelta("*** Begin Patch\n*** Update File: file.txt\n@@\n*** End Patch\n")
        }
        assertFailsWith<ApplyPatchException> {
            IncrementalPatchParser().pushDelta("*** Begin Patch\n*** Update File: file.txt\n@@\n*** End of File\n")
        }
        assertFailsWith<ApplyPatchException> {
            IncrementalPatchParser().pushDelta("*** Begin Patch\n*** Update File: file.txt\n@@\n@@\n")
        }
        assertFailsWith<ApplyPatchException> {
            IncrementalPatchParser().pushDelta("*** Begin Patch\n*** Update File: file.txt\n@@\n-old\nbad\n")
        }
    }
}
