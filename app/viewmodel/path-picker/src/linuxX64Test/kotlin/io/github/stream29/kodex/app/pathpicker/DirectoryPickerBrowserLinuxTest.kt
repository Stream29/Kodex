package io.github.stream29.kodex.app.pathpicker

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import platform.posix.symlink
import platform.posix.unlink
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs

val directoryPickerBrowserLinuxTest by testSuite {
    test("ignores a dangling symbolic link while listing child directories") {
        val unresolvedRoot = temporaryDirectory("directory-picker-dangling-link")
        SystemCoroutineFileSystem.createDirectories(unresolvedRoot)
        val root = SystemCoroutineFileSystem.resolve(unresolvedRoot)
        val directory = Path(root, "workspace")
        val child = Path(directory, "child")
        val danglingLink = Path(directory, "dangling")
        try {
            SystemCoroutineFileSystem.createDirectories(child)
            assertEquals(
                0,
                symlink(Path(directory, "missing").toString(), danglingLink.toString()),
            )

            val listing = assertIs<DirectoryPickerBrowserResult.Success>(
                DirectoryPickerBrowser().load(directory),
            ).listing

            assertEquals(listOf(child), listing.children)
        } finally {
            unlink(danglingLink.toString())
            deleteRecursively(root)
        }
    }
}

private fun temporaryDirectory(name: String): Path =
    Path(SystemTemporaryDirectory, "kodex-$name-${Random.nextLong()}")

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        for (child in SystemCoroutineFileSystem.list(path)) {
            deleteRecursively(child)
        }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
