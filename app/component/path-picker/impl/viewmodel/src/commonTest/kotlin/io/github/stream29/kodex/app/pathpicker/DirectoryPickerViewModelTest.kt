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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

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
                        browser = SystemDirectoryPickerBrowser(),
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
                        browser = SystemDirectoryPickerBrowser(),
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

    test("confirmation validates the current directory without waiting for child listing") {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            coroutineScope {
                val unresolvedRoot = temporaryDirectory("directory-picker-confirm-while-listing")
                SystemCoroutineFileSystem.createDirectories(unresolvedRoot)
                val root = SystemCoroutineFileSystem.resolve(unresolvedRoot)
                val fileSystem = DelayedDirectoryListingFileSystem(
                    delegate = SystemCoroutineFileSystem,
                    delayedDirectory = root,
                )
                val viewModel = DirectoryPickerViewModelImpl(
                    initialDirectory = root,
                    browser = SystemDirectoryPickerBrowser(fileSystem = fileSystem),
                    parentScope = this,
                )
                try {
                    withTimeout(1_000) { fileSystem.listStarted.await() }
                    viewModel.confirm()

                    val selected = withTimeout(1_000) {
                        assertIs<DirectoryPickerEffect.DirectorySelected>(viewModel.effects.first())
                    }
                    assertEquals(root, selected.directory)
                    assertIs<DirectoryPickerLoadState.Loading>(viewModel.state.value.loadState)
                } finally {
                    fileSystem.releaseList.complete(Unit)
                    viewModel.close()
                    deleteRecursively(root)
                }
            }
        }
    }

    test("confirmation reports a directory removed after listing instead of returning it") {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            coroutineScope {
                val unresolvedRoot = temporaryDirectory("directory-picker-removed-before-confirm")
                SystemCoroutineFileSystem.createDirectories(unresolvedRoot)
                val root = SystemCoroutineFileSystem.resolve(unresolvedRoot)
                val viewModel = DirectoryPickerViewModelImpl(
                    initialDirectory = root,
                    browser = SystemDirectoryPickerBrowser(),
                    parentScope = this,
                )
                try {
                    withTimeout(1_000) {
                        viewModel.state.first { it.loadState is DirectoryPickerLoadState.Ready }
                    }
                    SystemCoroutineFileSystem.delete(root)
                    viewModel.confirm()

                    val failed = withTimeout(1_000) {
                        assertIs<DirectoryPickerLoadState.Failed>(
                            viewModel.state.first {
                                it.loadState is DirectoryPickerLoadState.Failed
                            }.loadState,
                        )
                    }
                    assertEquals(root, failed.requestedDirectory)
                    assertNull(withTimeoutOrNull(100) { viewModel.effects.first() })
                } finally {
                    viewModel.close()
                    deleteRecursively(root)
                }
            }
        }
    }

    test("a late confirmation cannot select an earlier navigation target") {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            coroutineScope {
                val unresolvedRoot = temporaryDirectory("directory-picker-stale-confirm")
                SystemCoroutineFileSystem.createDirectories(unresolvedRoot)
                val root = SystemCoroutineFileSystem.resolve(unresolvedRoot)
                val slow = Path(root, "slow")
                val fast = Path(root, "fast")
                SystemCoroutineFileSystem.createDirectories(slow)
                SystemCoroutineFileSystem.createDirectories(fast)
                val fileSystem = DelayedDirectoryFileSystem(
                    delegate = SystemCoroutineFileSystem,
                    delayedDirectory = slow,
                )
                val viewModel = DirectoryPickerViewModelImpl(
                    initialDirectory = slow,
                    browser = SystemDirectoryPickerBrowser(fileSystem = fileSystem),
                    parentScope = this,
                )
                try {
                    withTimeout(1_000) { fileSystem.delayedResolveStarted.await() }
                    viewModel.confirm()
                    viewModel.navigateTo(fast)
                    withTimeout(1_000) {
                        viewModel.state.first {
                            (it.loadState as? DirectoryPickerLoadState.Ready)?.directory == fast
                        }
                    }
                    fileSystem.releaseDelayedResolve.complete(Unit)
                    assertNull(withTimeoutOrNull(100) { viewModel.effects.first() })

                    viewModel.confirm()
                    val selected = withTimeout(1_000) {
                        assertIs<DirectoryPickerEffect.DirectorySelected>(viewModel.effects.first())
                    }
                    assertEquals(fast, selected.directory)
                } finally {
                    fileSystem.releaseDelayedResolve.complete(Unit)
                    viewModel.close()
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
                        browser = SystemDirectoryPickerBrowser(fileSystem = fileSystem),
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

private class DelayedDirectoryListingFileSystem(
    private val delegate: CoroutineFileSystem,
    private val delayedDirectory: Path,
) : CoroutineFileSystem by delegate {
    val listStarted = CompletableDeferred<Unit>()
    val releaseList = CompletableDeferred<Unit>()

    override suspend fun list(directory: Path): Collection<Path> {
        if (directory == delayedDirectory) {
            listStarted.complete(Unit)
            releaseList.await()
        }
        return delegate.list(directory)
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
