package io.github.stream29.codex.lite.utils.applypatch

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail



private val fileSystem = SystemFileSystem

private fun wrapPatch(body: String): String =
    "*** Begin Patch\n$body\n*** End Patch"

private suspend fun apply(root: Path, patch: String): PatchApplyResult =
    patch.parsePatch().applyToFileSystem(root, SystemCoroutineFileSystem)

private fun temporaryRoot(): Path {
    val root = Path(SystemTemporaryDirectory, "codex-lite-apply-patch-rust-${Random.nextLong()}")
    fileSystem.createDirectories(root)
    return root
}

private suspend inline fun <reified T : Throwable> assertSuspendFailsWith(
    crossinline block: suspend () -> Unit,
): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) {
            return error
        }
        fail("Expected ${T::class.simpleName}, but caught ${error::class.simpleName}", error)
    }
    fail("Expected ${T::class.simpleName} to be thrown.")
}

private fun write(root: Path, path: String, content: String) {
    val file = Path(root, path)
    file.parent?.let { fileSystem.createDirectories(it) }
    val sink = fileSystem.sink(file).buffered()
    try {
        sink.writeString(content)
    } finally {
        sink.close()
    }
}

private fun read(root: Path, path: String): String =
    read(Path(root, path))

private fun read(path: Path): String {
    val source = fileSystem.source(path).buffered()
    return try {
        source.readString()
    } finally {
        source.close()
    }
}

private fun Path.patchPathOrRelative(relative: String): String =
    if (isAbsolute) toString() else relative

private fun deleteRecursively(path: Path) {
    val metadata = fileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        fileSystem.list(path).forEach(::deleteRecursively)
    }
    fileSystem.delete(path, mustExist = false)
}

val applyPatchBehaviorRustTest by testSuite {
    testFixture { temporaryRoot() } closeWith { deleteRecursively(this) } asParameterForEach {
        test("add file hunk creates file with contents") { root ->
            val result = apply(root,
                wrapPatch(
                    """
                    *** Add File: add.txt
                    +ab
                    +cd
                    """.trimIndent(),
                ),
            )

            assertEquals("ab\ncd\n", read(root, "add.txt"))
            assertEquals(listOf("add.txt"), result.affectedPaths.added)
            assertEquals(
                listOf(
                    PatchChange(
                        path = "add.txt",
                        change = PatchFileChange.Add(
                            content = "ab\ncd\n",
                            overwrittenContent = null,
                        ),
                    ),
                ),
                result.delta.changes,
            )
        }

        test("applies relative and absolute paths") { root ->
            val absoluteAddPath = Path(root, "absolute-add.txt")
            val absoluteDeletePath = Path(root, "absolute-delete.txt")
            val absoluteUpdatePath = Path(root, "absolute-update.txt")
            val absoluteAdd = absoluteAddPath.patchPathOrRelative("absolute-add.txt")
            val absoluteDelete = absoluteDeletePath.patchPathOrRelative("absolute-delete.txt")
            val absoluteUpdate = absoluteUpdatePath.patchPathOrRelative("absolute-update.txt")
            write(root, "relative-delete.txt", "delete relative\n")
            write(root, "absolute-delete.txt", "delete absolute\n")
            write(root, "relative-update.txt", "relative old\n")
            write(root, "absolute-update.txt", "absolute old\n")

            val result = apply(root,
                wrapPatch(
                    """
                    *** Add File: relative-add.txt
                    +relative add
                    *** Add File: $absoluteAdd
                    +absolute add
                    *** Delete File: relative-delete.txt
                    *** Delete File: $absoluteDelete
                    *** Update File: relative-update.txt
                    @@
                    -relative old
                    +relative new
                    *** Update File: $absoluteUpdate
                    @@
                    -absolute old
                    +absolute new
                    """.trimIndent(),
                ),
            )

            assertEquals("relative add\n", read(root, "relative-add.txt"))
            assertEquals("absolute add\n", read(absoluteAddPath))
            assertFalse(fileSystem.exists(Path(root, "relative-delete.txt")))
            assertFalse(fileSystem.exists(absoluteDeletePath))
            assertEquals("relative new\n", read(root, "relative-update.txt"))
            assertEquals("absolute new\n", read(absoluteUpdatePath))
            assertEquals(listOf("relative-add.txt", absoluteAdd), result.affectedPaths.added)
            assertEquals(listOf("relative-update.txt", absoluteUpdate), result.affectedPaths.modified)
            assertEquals(listOf("relative-delete.txt", absoluteDelete), result.affectedPaths.deleted)
        }

        test("delete file hunk removes file") { root ->
            write(root, "del.txt", "x")

            val result = apply(root, wrapPatch("*** Delete File: del.txt"))

            assertFalse(fileSystem.exists(Path(root, "del.txt")))
            assertEquals(
                PatchFileChange.Delete("x"),
                result.delta.changes.single().change,
            )
        }

        test("update file hunk modifies content") { root ->
            write(root, "update.txt", "foo\nbar\n")

            apply(root,
                wrapPatch(
                    """
                    *** Update File: update.txt
                    @@
                     foo
                    -bar
                    +baz
                    """.trimIndent(),
                ),
            )

            assertEquals("foo\nbaz\n", read(root, "update.txt"))
        }

        test("update file hunk can move file") { root ->
            write(root, "src.txt", "line\n")

            val result = apply(root,
                wrapPatch(
                    """
                    *** Update File: src.txt
                    *** Move to: dst.txt
                    @@
                    -line
                    +line2
                    """.trimIndent(),
                ),
            )

            assertFalse(fileSystem.exists(Path(root, "src.txt")))
            assertEquals("line2\n", read(root, "dst.txt"))
            assertEquals(listOf("dst.txt"), result.affectedPaths.modified)
            assertEquals(
                PatchFileChange.Update(
                    movePath = "dst.txt",
                    oldContent = "line\n",
                    overwrittenMoveContent = null,
                    newContent = "line2\n",
                ),
                result.delta.changes.single().change,
            )
        }

        test("multiple update chunks apply to single file") { root ->
            write(root, "multi.txt", "foo\nbar\nbaz\nqux\n")

            apply(root,
                wrapPatch(
                    """
                    *** Update File: multi.txt
                    @@
                     foo
                    -bar
                    +BAR
                    @@
                     baz
                    -qux
                    +QUX
                    """.trimIndent(),
                ),
            )

            assertEquals("foo\nBAR\nbaz\nQUX\n", read(root, "multi.txt"))
        }

        test("update file hunk interleaved changes") { root ->
            write(root, "interleaved.txt", "a\nb\nc\nd\ne\nf\n")

            apply(root,
                wrapPatch(
                    """
                    *** Update File: interleaved.txt
                    @@
                     a
                    -b
                    +B
                    @@
                     c
                     d
                    -e
                    +E
                    @@
                     f
                    +g
                    *** End of File
                    """.trimIndent(),
                ),
            )

            assertEquals("a\nB\nc\nd\nE\nf\ng\n", read(root, "interleaved.txt"))
        }

        test("pure addition chunk followed by removal") { root ->
            write(root, "panic.txt", "line1\nline2\nline3\n")

            apply(root,
                wrapPatch(
                    """
                    *** Update File: panic.txt
                    @@
                    +after-context
                    +second-line
                    @@
                     line1
                    -line2
                    -line3
                    +line2-replacement
                    """.trimIndent(),
                ),
            )

            assertEquals("line1\nline2-replacement\nafter-context\nsecond-line\n", read(root, "panic.txt"))
        }

        test("update line with unicode dash") { root ->
            write(root, "unicode.py", "import asyncio  # local import \u2013 avoids top\u2011level dep\n")

            apply(root,
                wrapPatch(
                    """
                    *** Update File: unicode.py
                    @@
                    -import asyncio  # local import - avoids top-level dep
                    +import asyncio  # HELLO
                    """.trimIndent(),
                ),
            )

            assertEquals("import asyncio  # HELLO\n", read(root, "unicode.py"))
        }

        test("reports missing context without changing target") { root ->
            write(root, "modify.txt", "line1\nline2\n")

            assertSuspendFailsWith<ApplyPatchException> {
                apply(root,
                    wrapPatch(
                        """
                        *** Update File: modify.txt
                        @@
                        -missing
                        +changed
                        """.trimIndent(),
                    ),
                )
            }

            assertEquals("line1\nline2\n", read(root, "modify.txt"))
        }

        test("add and move overwrite existing files") { root ->
            write(root, "duplicate.txt", "old content\n")
            write(root, "old/name.txt", "from\n")
            write(root, "renamed/dir/name.txt", "existing\n")

            val addResult = apply(root,
                wrapPatch(
                    """
                    *** Add File: duplicate.txt
                    +new content
                    """.trimIndent(),
                ),
            )
            val moveResult = apply(root,
                wrapPatch(
                    """
                    *** Update File: old/name.txt
                    *** Move to: renamed/dir/name.txt
                    @@
                    -from
                    +new
                    """.trimIndent(),
                ),
            )

            assertEquals("new content\n", read(root, "duplicate.txt"))
            assertEquals("new\n", read(root, "renamed/dir/name.txt"))
            assertFalse(fileSystem.exists(Path(root, "old/name.txt")))
            assertEquals("old content\n", (addResult.delta.changes.single().change as PatchFileChange.Add).overwrittenContent)
            assertEquals(
                "existing\n",
                (moveResult.delta.changes.single().change as PatchFileChange.Update).overwrittenMoveContent,
            )
        }

        test("update appends trailing newline") { root ->
            write(root, "no_newline.txt", "no newline at end")

            apply(root,
                wrapPatch(
                    """
                    *** Update File: no_newline.txt
                    @@
                    -no newline at end
                    +first line
                    +second line
                    """.trimIndent(),
                ),
            )

            assertEquals("first line\nsecond line\n", read(root, "no_newline.txt"))
        }

        test("failure after partial success leaves changes") { root ->
            assertSuspendFailsWith<ApplyPatchException> {
                apply(root,
                    wrapPatch(
                        """
                        *** Add File: created.txt
                        +hello
                        *** Update File: missing.txt
                        @@
                        -old
                        +new
                        """.trimIndent(),
                    ),
                )
            }

            assertEquals("hello\n", read(root, "created.txt"))
        }

        test("rejects empty patch and invalid operations") { root ->
            assertSuspendFailsWith<ApplyPatchException> {
                apply(root,
                    """
                    *** Begin Patch
                    *** End Patch
                    """.trimIndent(),
                )
            }
            assertSuspendFailsWith<ApplyPatchException> {
                apply(root,
                    """
                    *** Begin Patch
                    *** Update File: foo.txt
                    *** End Patch
                    """.trimIndent(),
                )
            }
            assertSuspendFailsWith<ApplyPatchException> {
                apply(root, wrapPatch("*** Delete File: missing.txt"))
            }
            assertSuspendFailsWith<ApplyPatchException> {
                apply(root,
                    """
                    *** Begin Patch
                    *** Frobnicate File: foo
                    *** End Patch
                    """.trimIndent(),
                )
            }
        }
    }
}
