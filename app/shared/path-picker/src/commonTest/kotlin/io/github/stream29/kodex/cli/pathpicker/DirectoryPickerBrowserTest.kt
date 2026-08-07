package io.github.stream29.kodex.cli.pathpicker

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals

val directoryPickerBrowserTest by testSuite {
    test("expands current user home shorthand before resolving a directory") {
        val unresolvedRoot = temporaryDirectory("directory-picker-home")
        SystemCoroutineFileSystem.createDirectories(unresolvedRoot)
        val root = SystemCoroutineFileSystem.resolve(unresolvedRoot)
        val home = Path(root, "home")
        val workspace = Path(home, "workspace")
        try {
            SystemCoroutineFileSystem.createDirectories(workspace)
            val browser = DirectoryPickerBrowser(userHome = home)

            val homeListing = browser.load(Path("~"))
            val workspaceListing = browser.load(Path("~", "workspace"))

            assertEquals(home, homeListing.directory)
            assertEquals(listOf(workspace), homeListing.children)
            assertEquals(workspace, workspaceListing.directory)
        } finally {
            deleteRecursively(root)
        }
    }

    test("resolves a directory and lists only its child directories in name order") {
        val unresolvedRoot = temporaryDirectory("directory-picker-browser")
        SystemCoroutineFileSystem.createDirectories(unresolvedRoot)
        val root = SystemCoroutineFileSystem.resolve(unresolvedRoot)
        val directory = Path(root, "workspace")
        val alpha = Path(directory, "alpha")
        val zoo = Path(directory, "Zoo")
        try {
            for (path in listOf(alpha, zoo)) {
                SystemCoroutineFileSystem.createDirectories(path)
            }
            SystemCoroutineFileSystem.writeString(Path(directory, "readme.txt"), "not selectable")

            val listing = DirectoryPickerBrowser().load(directory)

            assertEquals(SystemCoroutineFileSystem.resolve(directory), listing.directory)
            assertEquals(listOf(alpha, zoo), listing.children)
        } finally {
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
