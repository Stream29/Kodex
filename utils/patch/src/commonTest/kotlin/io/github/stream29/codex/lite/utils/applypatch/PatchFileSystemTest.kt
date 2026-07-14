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

private fun temporaryRoot(): Path {
    val root = Path(SystemTemporaryDirectory, "codex-lite-apply-patch-${Random.nextLong()}")
    fileSystem.createDirectories(root)
    return root
}

private suspend fun apply(root: Path, patch: String): PatchApplyResult =
    patch.parsePatch().applyToFileSystem(root, SystemCoroutineFileSystem)

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

private fun read(root: Path, path: String): String {
    val source = fileSystem.source(Path(root, path)).buffered()
    return try {
        source.readString()
    } finally {
        source.close()
    }
}

private fun deleteRecursively(path: Path) {
    val metadata = fileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        fileSystem.list(path).forEach(::deleteRecursively)
    }
    fileSystem.delete(path, mustExist = false)
}

val patchFileSystemTest by testSuite {
    testFixture { temporaryRoot() } closeWith { deleteRecursively(this) } asParameterForEach {
        test("apply adds updates and deletes files") { root ->
            write(root, "change.txt", "old\nkeep\n")
            write(root, "delete.txt", "remove\n")

            val result = apply(root,
                """
                *** Begin Patch
                *** Add File: added.txt
                +hello
                +world
                *** Update File: change.txt
                @@
                -old
                +new
                 keep
                *** Delete File: delete.txt
                *** End Patch
                """.trimIndent(),
            )

            assertEquals("hello\nworld\n", read(root, "added.txt"))
            assertEquals("new\nkeep\n", read(root, "change.txt"))
            assertFalse(fileSystem.exists(Path(root, "delete.txt")))
            assertEquals(listOf("added.txt"), result.affectedPaths.added)
            assertEquals(listOf("change.txt"), result.affectedPaths.modified)
            assertEquals(listOf("delete.txt"), result.affectedPaths.deleted)
            assertEquals(3, result.delta.changes.size)
        }

        test("apply moves updated file") { root ->
            write(root, "old.txt", "before\n")

            apply(root,
                """
                *** Begin Patch
                *** Update File: old.txt
                *** Move to: new.txt
                @@
                -before
                +after
                *** End Patch
                """.trimIndent(),
            )

            assertFalse(fileSystem.exists(Path(root, "old.txt")))
            assertEquals("after\n", read(root, "new.txt"))
        }

        test("change context disambiguates update location") { root ->
            write(
                root,
                "multi.txt",
                "fun a\nvalue = 1\nfun b\nvalue = 1\n",
            )

            apply(root,
                """
                *** Begin Patch
                *** Update File: multi.txt
                @@ fun b
                -value = 1
                +value = 2
                *** End Patch
                """.trimIndent(),
            )

            assertEquals("fun a\nvalue = 1\nfun b\nvalue = 2\n", read(root, "multi.txt"))
        }

        test("end of file anchors update at tail") { root ->
            write(root, "tail.txt", "first\nlast\n")

            apply(root,
                """
                *** Begin Patch
                *** Update File: tail.txt
                @@
                -last
                +end
                *** End of File
                *** End Patch
                """.trimIndent(),
            )

            assertEquals("first\nend\n", read(root, "tail.txt"))
        }

        test("failed patch keeps earlier committed changes") { root ->
            write(root, "target.txt", "actual\n")

            assertSuspendFailsWith<ApplyPatchException> {
                apply(root,
                    """
                    *** Begin Patch
                    *** Add File: created.txt
                    +created
                    *** Update File: target.txt
                    @@
                    -missing
                    +changed
                    *** End Patch
                    """.trimIndent(),
                )
            }

            assertEquals("created\n", read(root, "created.txt"))
            assertEquals("actual\n", read(root, "target.txt"))
        }
    }

    test("parses standalone patch shape") {
        val patch = """
            *** Begin Patch
            *** Add File: sample.txt
            +text
            *** End Patch
            """.trimIndent().parsePatch()

        assertEquals(listOf(AddFileHunk("sample.txt", "text\n")), patch.hunks)
        assertEquals(
            """
            *** Begin Patch
            *** Add File: sample.txt
            +text
            *** End Patch
            """.trimIndent(),
            patch.patch,
        )
    }

    test("parses environment id and heredoc wrapper") {
        val patch = """
            <<'EOF'
            *** Begin Patch
            *** Environment ID: local
            *** Delete File: gone.txt
            *** End Patch
            EOF
            """.trimIndent().parsePatch()

        assertEquals("local", patch.environmentId)
        assertEquals(listOf(DeleteFileHunk("gone.txt")), patch.hunks)
    }
}
