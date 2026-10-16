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
    test("declared dependency alone supports loading retry filtering and confirmation") {
        runTest {
            val directory = Path("/picker")
            val child = Path(directory, "Alpha")
            var loads = 0
            var validations = 0
            val dependencies = object : DirectoryPickerDependencies {
                override val browser = object : DirectoryPickerBrowser {
                    override suspend fun load(directory: Path): DirectoryPickerLoadResult {
                        loads++
                        return if (loads == 1) {
                            DirectoryPickerLoadResult.Failure(DirectoryPickerFailure.NotDirectory(directory))
                        } else {
                            DirectoryPickerLoadResult.Success(DirectoryPickerListing(directory, listOf(child)))
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
