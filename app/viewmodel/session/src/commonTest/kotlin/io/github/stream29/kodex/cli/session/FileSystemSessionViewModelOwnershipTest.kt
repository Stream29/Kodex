package io.github.stream29.kodex.cli.session

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.filesystem.FileSystemKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

private suspend fun temporaryViewModelRepositoryRoot(): Path =
    Path(SystemTemporaryDirectory, "kodex-session-view-model-${Random.nextLong()}").also { root ->
        SystemCoroutineFileSystem.createDirectories(root)
    }

val fileSystemSessionViewModelOwnershipTest by testSuite {
    testFixture { temporaryViewModelRepositoryRoot() } closeWith {
        deleteViewModelRepositoryRecursively(this)
    } asParameterForEach {
        test("releasing a Session ViewModel closes its repository and root lease") { root ->
            coroutineScope {
                val repositories = mutableListOf<FileSystemKodexSessionRepository>()
                val repositoryFactory = KodexSessionRepositoryFactory { ownerScope ->
                    ownerScope.FileSystemKodexSessionRepository(
                        root = root,
                        dependencies = testKodexAgentDependencies(),
                    ).also(repositories::add)
                }
                val registry = testSessionViewModelRegistry(repositoryFactory, this)
                try {
                    val model = registry.create { sessionIndex ->
                        KodexAgentSettings(
                            model = OpenAiModelId("test-model"),
                            threadName = "Session $sessionIndex",
                        )
                    }
                    val lock = Path(root, "sessions/${model.sessionIndex}/lock.json")
                    val repository = repositories.single()
                    assertTrue(repository.coroutineContext[Job]?.isActive == true)
                    assertTrue(SystemCoroutineFileSystem.exists(lock))
                    assertSame(model, registry.open(model.sessionIndex))
                    assertTrue(repository.coroutineContext[Job]?.isActive == true)
                    assertTrue(SystemCoroutineFileSystem.exists(lock))

                    registry.release(model.sessionIndex)

                    assertFalse(repository.coroutineContext[Job]?.isActive == true)
                    assertFalse(SystemCoroutineFileSystem.exists(lock))
                    val reopened = FileSystemKodexSessionRepository(
                        root = root,
                        dependencies = testKodexAgentDependencies(),
                    )
                    try {
                        assertEquals(
                            "Session ${model.sessionIndex}",
                            reopened.open(model.sessionIndex).storage.settings[0].threadName,
                        )
                    } finally {
                        reopened.cancelAndJoin()
                    }
                } finally {
                    registry.shutdown()
                }
            }
        }

        test("closing a Catalog ViewModel closes its repository during repair") { root ->
            coroutineScope {
                val dependencies = testKodexAgentDependencies()
                val setup = FileSystemKodexSessionRepository(root, dependencies)
                val index = setup.create()
                setup.open(index).runtime.modify { storage ->
                    storage.initialize(
                        KodexAgentSettings(
                            model = OpenAiModelId("test-model"),
                            threadName = "Catalog",
                        ),
                    )
                    storage.timestamp[1] = Instant.parse("2026-08-24T08:00:00Z")
                }
                setup.cancelAndJoin()
                val latest = Path(root, "sessions/$index/timestamp/latest.json")
                val lock = Path(root, "sessions/$index/lock.json")
                SystemCoroutineFileSystem.writeString(latest, "2")

                val fileSystem = SuspendingViewModelTimelineFileSystem("timestamp")
                lateinit var repository: FileSystemKodexSessionRepository
                val repositoryFactory = KodexSessionRepositoryFactory { ownerScope ->
                    ownerScope.FileSystemKodexSessionRepository(
                        root = root,
                        dependencies = dependencies,
                        fileSystem = fileSystem,
                    ).also { repository = it }
                }
                val catalog = createSessionCatalogViewModelFactory(repositoryFactory, this).create { false }
                val refresh = async { catalog.refresh() }
                fileSystem.listStarted.await()
                assertTrue(repository.coroutineContext[Job]?.isActive == true)
                assertTrue(SystemCoroutineFileSystem.exists(lock))

                catalog.close()

                assertFailsWith<CancellationException> { refresh.await() }
                repository.coroutineContext[Job]?.join()
                assertFalse(repository.coroutineContext[Job]?.isActive == true)
                assertFalse(SystemCoroutineFileSystem.exists(lock))
                assertEquals("2", SystemCoroutineFileSystem.readString(latest))
            }
        }
    }
}

private class SuspendingViewModelTimelineFileSystem(
    private val timelineName: String,
    private val delegate: CoroutineFileSystem = SystemCoroutineFileSystem,
) : CoroutineFileSystem by delegate {
    val listStarted = CompletableDeferred<Unit>()

    override suspend fun list(directory: Path): Collection<Path> {
        if (directory.name == timelineName) {
            listStarted.complete(Unit)
            awaitCancellation()
        }
        return delegate.list(directory)
    }
}

private suspend fun deleteViewModelRepositoryRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child ->
            deleteViewModelRepositoryRecursively(child)
        }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
