package io.github.stream29.kodex.app.pathpicker

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerFailure
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerLoadResult
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerValidationResult
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

val directoryPickerBrowserFailureTest by testSuite {
    for (listing in listOf(false, true)) {
        test("ordinary filesystem failure remains displayable listing=$listing") {
            val browser = failingBrowser(IOException("Synthetic filesystem failure"), listing)
            val failure = if (listing) {
                assertIs<DirectoryPickerLoadResult.Failure>(browser.load(SystemTemporaryDirectory)).failure
            } else {
                assertIs<DirectoryPickerValidationResult.Failure>(browser.validate(SystemTemporaryDirectory)).failure
            }
            val fileSystemFailure = assertIs<DirectoryPickerFailure.FileSystem>(failure)
            assertTrue(fileSystemFailure.detail.contains("Synthetic filesystem failure"))
        }
        test("operation cancellation propagates unchanged listing=$listing") {
            val cancelled = CancellationException("Synthetic operation cancellation")
            val browser = failingBrowser(cancelled, listing)
            val actual = assertFailsWith<CancellationException> {
                if (listing) browser.load(SystemTemporaryDirectory)
                else browser.validate(SystemTemporaryDirectory)
            }
            assertSame(cancelled, actual)
            currentCoroutineContext().ensureActive()
        }
        test("implementation errors are not downgraded to filesystem failures listing=$listing") {
            val error = AssertionError("Synthetic filesystem implementation error")
            val browser = failingBrowser(error, listing)
            val actual = assertFailsWith<AssertionError> {
                if (listing) browser.load(SystemTemporaryDirectory)
                else browser.validate(SystemTemporaryDirectory)
            }
            assertSame(error, actual)
        }
    }
}

private fun failingBrowser(failure: Throwable, listing: Boolean): SystemDirectoryPickerBrowser =
    SystemDirectoryPickerBrowser(
        fileSystem = object : CoroutineFileSystem by SystemCoroutineFileSystem {
            override suspend fun resolve(path: Path): Path =
                if (listing) SystemCoroutineFileSystem.resolve(path) else throw failure

            override suspend fun list(directory: Path): Collection<Path> = throw failure
        },
        userHome = null,
    )
