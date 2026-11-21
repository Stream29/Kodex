package io.github.stream29.kodex.app.migration

import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

internal val homeTestFileSystem = SystemCoroutineFileSystem

internal suspend fun newTestHome(): Path =
    Path(SystemTemporaryDirectory, "kodex-home-test-${Random.nextLong().toULong()}").also {
        homeTestFileSystem.createDirectories(it, mustCreate = true)
    }

internal suspend fun readyTestHome(): Path {
    val home = newTestHome()
    try {
        coroutineScope { prepareKodexHome(home).closeAndJoin() }
        return home
    } catch (failure: Throwable) {
        deleteTestHome(home)
        throw failure
    }
}

internal suspend fun deleteTestHome(path: Path): Unit = withContext(NonCancellable) {
    deleteTestTree(path)
}

private suspend fun deleteTestTree(path: Path) {
    val metadata = homeTestFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) homeTestFileSystem.list(path).forEach { deleteTestTree(it) }
    homeTestFileSystem.delete(path, mustExist = false)
}

internal suspend fun writeFixtureCheckpoint(home: Path, version: String) =
    homeTestFileSystem.writeString(Path(home, HomeVersionFile), readyMarker(version))

internal fun readyMarker(version: String): String = """{"generation":1,"checkpoint":"$version"}"""

internal fun initializingMarker(version: String): String =
    """{"generation":1,"checkpoint":"$version","state":"initializing"}"""
