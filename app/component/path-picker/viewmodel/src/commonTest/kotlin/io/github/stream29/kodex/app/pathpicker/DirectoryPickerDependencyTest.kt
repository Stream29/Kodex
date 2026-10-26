@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.pathpicker

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.pathpicker.contract.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

val directoryPickerDependencyTest by testSuite {
    test("names and parent targets come from directory metadata rather than frontend path syntax") {
        runTest {
            val directory = Path("/opaque")
            val child = Path("/entry-token")
            val parent = Path("/parent-token")
            val requests = mutableListOf<Path>()
            val dependencies = object : DirectoryPickerDependencies {
                override val browser = object : DirectoryPickerBrowser {
                    override suspend fun load(directory: Path): DirectoryPickerLoadResult {
                        requests += directory
                        return if (directory == parent) {
                            DirectoryPickerLoadResult.Failure(DirectoryPickerFailure.FileSystem("Unavailable"), child)
                        } else {
                            DirectoryPickerLoadResult.Success(DirectoryPickerListing(
                                directory, listOf(child), parent, mapOf(child to "Display name"),
                            ))
                        }
                    }
                    override suspend fun validate(directory: Path): DirectoryPickerValidationResult =
                        DirectoryPickerValidationResult.Failure(DirectoryPickerFailure.NotDirectory(directory), child)
                }
            }
            val picker = createDirectoryPickerViewModel(directory, dependencies, this)
            try {
                runCurrent()
                picker.updateFilter("DISPLAY")
                assertEquals(listOf(child), picker.state.value.visibleChildren)
                picker.updateFilter("entry-token")
                assertTrue(picker.state.value.visibleChildren.isEmpty())
                picker.navigateUp()
                runCurrent()
                assertEquals(listOf(directory, parent), requests)
                assertEquals(child, picker.state.value.parentDirectory)
                picker.confirm()
                runCurrent()
                assertEquals(child, picker.state.value.parentDirectory)
                picker.navigateUp()
                runCurrent()
                assertEquals(listOf(directory, parent, child), requests)
            } finally { picker.close() }
        }
    }

    test("declared dependency alone supports loading retry filtering and confirmation") {
        runTest {
            val directory = Path("/picker")
            val child = Path("/picker/Alpha")
            var loads = 0
            var validations = 0
            val dependencies = object : DirectoryPickerDependencies {
                override val browser = object : DirectoryPickerBrowser {
                    override suspend fun load(directory: Path): DirectoryPickerLoadResult {
                        loads++
                        return if (loads == 1) {
                            DirectoryPickerLoadResult.Failure(DirectoryPickerFailure.NotDirectory(directory))
                        } else {
                            DirectoryPickerLoadResult.Success(DirectoryPickerListing(directory, listOf(child), null, mapOf(child to "Alpha")))
                        }
                    }

                    override suspend fun validate(directory: Path): DirectoryPickerValidationResult {
                        validations++
                        return DirectoryPickerValidationResult.Success(directory)
                    }
                }
            }
            val picker = createDirectoryPickerViewModel(directory, dependencies, this)
            try {
                assertIs<DirectoryPickerLoadState.Loading>(picker.state.value.loadState)
                runCurrent()
                assertIs<DirectoryPickerLoadState.Failed>(picker.state.value.loadState)
                picker.updateFilter("AL")
                picker.retry()
                runCurrent()
                assertEquals("AL", picker.state.value.filterQuery)
                assertEquals(listOf(child), picker.state.value.visibleChildren)
                picker.retry()
                runCurrent()
                assertEquals(2, loads)
                picker.confirm()
                picker.confirm()
                runCurrent()
                assertEquals(1, validations)
                assertEquals(
                    DirectoryPickerEffect.DirectorySelected(directory),
                    picker.effects.first(),
                )
            } finally {
                picker.close()
            }
        }
    }

    test("close cancels child work and later interactions do not change state") {
        runTest {
            val started = CompletableDeferred<Unit>()
            val pending = CompletableDeferred<DirectoryPickerLoadResult>()
            var cancelled = false
            val dependencies = object : DirectoryPickerDependencies {
                override val browser = object : DirectoryPickerBrowser {
                    override suspend fun load(directory: Path): DirectoryPickerLoadResult {
                        started.complete(Unit)
                        return try {
                            pending.await()
                        } finally {
                            cancelled = true
                        }
                    }

                    override suspend fun validate(directory: Path) =
                        DirectoryPickerValidationResult.Success(directory)
                }
            }
            val picker = createDirectoryPickerViewModel(Path("/picker"), dependencies, this)
            runCurrent()
            started.await()
            val before = picker.state.value
            picker.close()
            picker.close()
            picker.navigateTo(Path("/other"))
            picker.navigateUp()
            picker.updateFilter("ignored")
            picker.retry()
            picker.confirm()
            runCurrent()
            assertTrue(cancelled)
            assertEquals(before, picker.state.value)
        }
    }
}
