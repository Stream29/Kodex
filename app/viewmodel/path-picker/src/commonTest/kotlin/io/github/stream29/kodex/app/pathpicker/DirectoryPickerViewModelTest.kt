package io.github.stream29.kodex.app.pathpicker

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerEffect
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerFailure
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerLoadState
import io.github.stream29.kodex.app.pathpicker.contract.visibleChildren
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs

val directoryPickerViewModelTest by testSuite {
    test("publishes ready state, shared filtering, and a resolved selection effect") {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            coroutineScope {
                val unresolvedRoot = temporaryDirectory("directory-picker-view-model")
                SystemCoroutineFileSystem.createDirectories(unresolvedRoot)
                val root = SystemCoroutineFileSystem.resolve(unresolvedRoot)
                val alpha = Path(root, "Alpha")
                val beta = Path(root, "beta")
                try {
                    SystemCoroutineFileSystem.createDirectories(alpha)
                    SystemCoroutineFileSystem.createDirectories(beta)
                    val viewModel = DirectoryPickerViewModelImpl(
                        initialDirectory = unresolvedRoot,
                        browser = DirectoryPickerBrowser(),
                        parentScope = this,
                    )
                    try {
                        val ready = withTimeout(1_000) {
                            assertIs<DirectoryPickerLoadState.Ready>(
                                viewModel.state.first { state ->
                                    state.loadState is DirectoryPickerLoadState.Ready
                                }.loadState,
                            )
                        }
                        assertEquals(root, ready.directory)
                        assertEquals(listOf(alpha, beta), ready.children)

                        viewModel.updateFilter("AL")
                        assertEquals(listOf(alpha), viewModel.state.value.visibleChildren)

                        viewModel.confirm()
                        val selected = withTimeout(1_000) {
                            assertIs<DirectoryPickerEffect.DirectorySelected>(viewModel.effects.first())
                        }
                        assertEquals(root, selected.directory)
                    } finally {
                        viewModel.close()
                    }
                } finally {
                    deleteRecursively(root)
                }
            }
        }
    }

    test("publishes a typed not-directory failure") {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            coroutineScope {
                val unresolvedRoot = temporaryDirectory("directory-picker-not-directory")
                SystemCoroutineFileSystem.createDirectories(unresolvedRoot)
                val root = SystemCoroutineFileSystem.resolve(unresolvedRoot)
                val file = Path(root, "file.txt")
                try {
                    SystemCoroutineFileSystem.writeString(file, "content")
                    val viewModel = DirectoryPickerViewModelImpl(
                        initialDirectory = file,
                        browser = DirectoryPickerBrowser(),
                        parentScope = this,
                    )
                    try {
                        val failed = withTimeout(1_000) {
                            assertIs<DirectoryPickerLoadState.Failed>(
                                viewModel.state.first { state ->
                                    state.loadState is DirectoryPickerLoadState.Failed
                                }.loadState,
                            )
                        }
                        val failure = assertIs<DirectoryPickerFailure.NotDirectory>(failed.failure)
                        assertEquals(file, failure.directory)
                    } finally {
                        viewModel.close()
                    }
                } finally {
                    deleteRecursively(root)
                }
            }
        }
    }

    test("a stale load completion cannot replace a newer navigation request") {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            coroutineScope {
                val unresolvedRoot = temporaryDirectory("directory-picker-load-revision")
                SystemCoroutineFileSystem.createDirectories(unresolvedRoot)
                val root = SystemCoroutineFileSystem.resolve(unresolvedRoot)
                val slow = Path(root, "slow")
                val fast = Path(root, "fast")
                try {
                    SystemCoroutineFileSystem.createDirectories(slow)
                    SystemCoroutineFileSystem.createDirectories(fast)
                    val fileSystem = DelayedDirectoryFileSystem(
                        delegate = SystemCoroutineFileSystem,
                        delayedDirectory = slow,
                    )
                    val viewModel = DirectoryPickerViewModelImpl(
                        initialDirectory = slow,
                        browser = DirectoryPickerBrowser(fileSystem = fileSystem),
                        parentScope = this,
                    )
                    try {
                        withTimeout(1_000) { fileSystem.delayedResolveStarted.await() }
                        viewModel.navigateTo(fast)

                        val ready = withTimeout(1_000) {
                            assertIs<DirectoryPickerLoadState.Ready>(
                                viewModel.state.first { state ->
                                    (state.loadState as? DirectoryPickerLoadState.Ready)
                                        ?.directory == fast
                                }.loadState,
                            )
                        }
                        assertEquals(2, ready.requestId)

                        fileSystem.releaseDelayedResolve.complete(Unit)
                        withTimeout(1_000) { fileSystem.delayedListCompleted.await() }

                        assertEquals(ready, viewModel.state.value.loadState)
                    } finally {
                        viewModel.close()
                    }
                } finally {
                    deleteRecursively(root)
                }
            }
        }
    }
}

private class DelayedDirectoryFileSystem(
    private val delegate: CoroutineFileSystem,
    private val delayedDirectory: Path,
) : CoroutineFileSystem by delegate {
    val delayedResolveStarted = CompletableDeferred<Unit>()
    val releaseDelayedResolve = CompletableDeferred<Unit>()
    val delayedListCompleted = CompletableDeferred<Unit>()

    override suspend fun resolve(path: Path): Path {
        if (path == delayedDirectory) {
            delayedResolveStarted.complete(Unit)
            releaseDelayedResolve.await()
        }
        return delegate.resolve(path)
    }

    override suspend fun list(directory: Path): Collection<Path> {
        val children = delegate.list(directory)
        if (directory == delayedDirectory) {
            delayedListCompleted.complete(Unit)
        }
        return children
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
