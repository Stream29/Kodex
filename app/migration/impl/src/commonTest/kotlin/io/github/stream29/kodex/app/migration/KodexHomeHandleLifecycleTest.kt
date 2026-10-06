package io.github.stream29.kodex.app.migration

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.filesystemlease.FileSystemLeaseInUseException
import io.github.stream29.kodex.utils.filesystemlease.FileSystemWriteLease
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

public val kodexHomeHandleLifecycleTest by testSuite {
    testFixture { lifecycleHome() } closeWith {
        val home = this
        withContext(NonCancellable) { deleteLifecycleHome(home) }
    } asParameterForEach {
        test("real public factory returns the sole interface and repeated close permits restart") { home ->
            withHomeOwner { owner ->
                // Static proof uses the public factory, not a fake contract implementation.
                val handle: KodexHomeHandle = owner.prepareKodexHome(home)
                val closeable: AutoCloseable = handle
                assertEquals(home, handle.home)
                assertEquals(CurrentKodexApplicationVersion, handle.version)
                assertEquals(1, readOwners(home).size)
                assertFailsWith<FileSystemLeaseInUseException> {
                    owner.FileSystemWriteLease(homeLocks(home))
                }

                closeable.close()
                handle.close()
                handle.closeAndJoin()
                handle.closeAndJoin()

                assertTrue(owner.coroutineContext.job.isActive)
                assertTrue(readOwners(home).isEmpty())
                assertWriteAvailable(owner, home)
                val restarted: KodexHomeHandle = owner.prepareKodexHome(home)
                restarted.closeAndJoin()
                assertTrue(readOwners(home).isEmpty())
            }
        }

        test("closing one Home handle retains the other shared read reference") { home ->
            withHomeOwner { owner ->
                val first: KodexHomeHandle = owner.prepareKodexHome(home)
                val second: KodexHomeHandle = owner.prepareKodexHome(home)
                try {
                    first.closeAndJoin()
                    first.closeAndJoin()
                    assertEquals(1, readOwners(home).size)
                    assertFailsWith<FileSystemLeaseInUseException> {
                        owner.FileSystemWriteLease(homeLocks(home))
                    }
                    second.closeAndJoin()
                    assertTrue(readOwners(home).isEmpty())
                    assertWriteAvailable(owner, home)
                } finally {
                    first.closeAndJoin()
                    second.closeAndJoin()
                }
            }
        }

        test("owner cancellation waits for retained Home lease release without explicit close") { home ->
            withHomeOwner { owner ->
                val handle: KodexHomeHandle = owner.prepareKodexHome(home)
                assertEquals(1, readOwners(home).size)
                owner.coroutineContext.job.cancelAndJoin()
                assertTrue(readOwners(home).isEmpty())
                handle.closeAndJoin()
            }
            withHomeOwner { nextOwner ->
                assertWriteAvailable(nextOwner, home)
                nextOwner.prepareKodexHome(home).closeAndJoin()
            }
        }

        test("closeAndJoin cancellation stops waiting but does not revoke the close request") { home ->
            val cleanupStarted = CompletableDeferred<Unit>()
            val allowCleanup = CompletableDeferred<Unit>()
            val cleanupFinished = CompletableDeferred<Unit>()
            val fileSystem = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun delete(path: Path, mustExist: Boolean) {
                    if (path.name.endsWith(".read.lock")) {
                        cleanupStarted.complete(Unit)
                        allowCleanup.await()
                        SystemCoroutineFileSystem.delete(path, mustExist)
                        cleanupFinished.complete(Unit)
                    } else {
                        SystemCoroutineFileSystem.delete(path, mustExist)
                    }
                }
            }
            try {
                withHomeOwner { owner ->
                    val handle: KodexHomeHandle = owner.prepareKodexHome(home, fileSystem)
                    coroutineScope {
                        val waitCancelled = CompletableDeferred<Unit>()
                        val closer = launch {
                            try {
                                handle.closeAndJoin()
                                error("Cleanup has not been released.")
                            } catch (_: CancellationException) {
                                waitCancelled.complete(Unit)
                            }
                        }
                        try {
                            cleanupStarted.await()
                            assertFalse(cleanupFinished.isCompleted)
                            closer.cancelAndJoin()
                            assertTrue(waitCancelled.isCompleted)
                            assertFalse(cleanupFinished.isCompleted)
                            assertEquals(1, readOwners(home).size)
                        } finally {
                            allowCleanup.complete(Unit)
                            handle.closeAndJoin()
                        }
                    }
                    assertTrue(cleanupFinished.isCompleted)
                    assertTrue(readOwners(home).isEmpty())
                    assertWriteAvailable(owner, home)
                }
            } finally {
                allowCleanup.complete(Unit)
            }
        }

        test("migration cancellation preserves the stored version and releases its write lease") { home ->
            SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"1.0.0\"")
            val actionStarted = CompletableDeferred<Unit>()
            withHomeOwner { owner ->
                coroutineScope {
                    val preparation = launch {
                        owner.prepareKodexHome(
                            home = home,
                            currentVersion = MigrationVersion("2.0.0"),
                            migrations = listOf(
                                Migration(MigrationVersion("1.1.0")) { _, _ ->
                                    actionStarted.complete(Unit)
                                    awaitCancellation()
                                },
                            ),
                            fileSystem = SystemCoroutineFileSystem,
                        ).closeAndJoin()
                    }
                    actionStarted.await()
                    preparation.cancelAndJoin()
                }
                assertEquals("\"1.0.0\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
                assertTrue(SystemCoroutineFileSystem.list(homeLocks(home)).isEmpty())
                assertWriteAvailable(owner, home)
            }
        }

        test("public factory exposes version read failure cause and releases its read lease") { home ->
            val readFailure = IOException("isolated version read failure")
            val fileSystem = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun readBytes(path: Path, maxByteCount: Long): ByteArray {
                    if (path.name == "version.json") throw readFailure
                    return SystemCoroutineFileSystem.readBytes(path, maxByteCount)
                }
            }
            withHomeOwner { owner ->
                val failure = assertFailsWith<KodexHomeVersionException> {
                    owner.prepareKodexHome(home, fileSystem)
                }
                assertSame(readFailure, failure.cause)
                assertTrue(failure.suppressedExceptions.isEmpty())
                assertTrue(readOwners(home).isEmpty())
                assertWriteAvailable(owner, home)
            }
        }

        test("public factory rejects a noncanonical version through the real exception contract") { home ->
            SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"01.0.0\"")
            withHomeOwner { owner ->
                val failure = assertFailsWith<KodexHomeVersionException> {
                    owner.prepareKodexHome(home)
                }
                assertTrue(failure.cause is IllegalArgumentException)
                assertEquals("\"01.0.0\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
                assertTrue(readOwners(home).isEmpty())
                assertWriteAvailable(owner, home)
            }
        }

        test("public factory rejects a non-directory version without rewriting it") { home ->
            SystemCoroutineFileSystem.delete(Path(home, "version.json"))
            SystemCoroutineFileSystem.createDirectories(Path(home, "version.json"))
            withHomeOwner { owner ->
                assertFailsWith<KodexHomeVersionException> { owner.prepareKodexHome(home) }
                assertTrue(SystemCoroutineFileSystem.metadataOrNull(Path(home, "version.json"))!!.isDirectory)
                assertTrue(readOwners(home).isEmpty())
                assertWriteAvailable(owner, home)
            }
        }

        test("public factory exposes the real layout exception and does not stamp an invalid Home") { home ->
            SystemCoroutineFileSystem.delete(Path(home, "version.json"))
            SystemCoroutineFileSystem.createDirectories(Path(home, "sessions", "0"))
            withHomeOwner { owner ->
                val failure = assertFailsWith<KodexHomeLayoutException> {
                    owner.prepareKodexHome(home)
                }
                val versionFailure: KodexHomeVersionException = failure
                assertSame(failure, versionFailure)
                assertTrue(failure.cause != null)
                assertFalse(SystemCoroutineFileSystem.exists(Path(home, "version.json")))
                assertTrue(SystemCoroutineFileSystem.list(homeLocks(home)).isEmpty())
                assertWriteAvailable(owner, home)
            }
        }

        test("baseline migration primary failure is not replaced by background cleanup failure") { home ->
            SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"1.0.0\"")
            val primary = IOException("isolated migration primary")
            val cleanup = IOException("isolated write-owner cleanup")
            val backgroundFailure = CompletableDeferred<Throwable>()
            val fileSystem = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun delete(path: Path, mustExist: Boolean) {
                    if (path.name.endsWith(".write.lock")) throw cleanup
                    SystemCoroutineFileSystem.delete(path, mustExist)
                }
            }
            withHomeOwner(
                handler = CoroutineExceptionHandler { _, failure -> backgroundFailure.complete(failure) },
            ) { owner ->
                val failure = assertFailsWith<IOException> {
                    owner.prepareKodexHome(
                        home = home,
                        currentVersion = MigrationVersion("2.0.0"),
                        migrations = listOf(Migration(MigrationVersion("1.1.0")) { _, _ -> throw primary }),
                        fileSystem = fileSystem,
                    )
                }
                assertSame(primary, failure)
                // Characterization, not a new primary/suppressed protocol guarantee:
                // lease Job.join does not rethrow the child cleanup exception.
                // JVM coroutine stack-trace recovery can copy an IOException and retain
                // the original as its cause. Verify the injected failure, not copy identity.
                val reportedCleanup = backgroundFailure.await()
                assertTrue(
                    generateSequence(reportedCleanup) { it.cause }.any { it === cleanup },
                    "The background handler must receive the injected cleanup failure.",
                )
                assertTrue(failure.suppressedExceptions.isEmpty())
                assertEquals("\"1.0.0\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
                assertTrue(SystemCoroutineFileSystem.list(homeLocks(home)).any { it.name.endsWith(".write.lock") })
            }
        }

        test("baseline cancellation during read-owner publication strands a lock before handle handoff") { home ->
            val published = CompletableDeferred<Unit>()
            val fileSystem = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun writeString(
                    path: Path,
                    content: String,
                    append: Boolean,
                    mustCreate: Boolean,
                ) {
                    SystemCoroutineFileSystem.writeString(path, content, append, mustCreate)
                    if (path.name.endsWith(".read.lock")) {
                        published.complete(Unit)
                        // Actual bytes exist, but acquireRenewableFileSystemLease has
                        // not yet constructed the owner-bound renewable lease.
                        awaitCancellation()
                    }
                }
            }
            withHomeOwner { owner ->
                coroutineScope {
                    val preparation = launch { owner.prepareKodexHome(home, fileSystem).closeAndJoin() }
                    published.await()
                    preparation.cancelAndJoin()
                }
                owner.coroutineContext.job.cancelAndJoin()
                // Deliberately records the preexisting defect; no protocol rewrite.
                assertEquals(1, readOwners(home).size)
                assertFalse(SystemCoroutineFileSystem.exists(Path(homeLocks(home), "guard.lock")))
                assertEquals(
                    "\"$CurrentKodexApplicationVersion\"",
                    SystemCoroutineFileSystem.readString(Path(home, "version.json")),
                )
            }
        }
    }
}

private suspend fun <T> withHomeOwner(
    handler: CoroutineExceptionHandler? = null,
    block: suspend (CoroutineScope) -> T,
): T = coroutineScope {
    val ownerJob = SupervisorJob(coroutineContext.job)
    val ownerContext = coroutineContext + ownerJob
    val owner = CoroutineScope(if (handler == null) ownerContext else ownerContext + handler)
    try {
        block(owner)
    } finally {
        withContext(NonCancellable) { ownerJob.cancelAndJoin() }
    }
}

private fun homeLocks(home: Path): Path = Path(home, ".locks", "home")

private suspend fun readOwners(home: Path): List<Path> =
    SystemCoroutineFileSystem.list(homeLocks(home)).filter { it.name.endsWith(".read.lock") }

private suspend fun assertWriteAvailable(owner: CoroutineScope, home: Path) {
    val lease = owner.FileSystemWriteLease(homeLocks(home))
    lease.close()
    lease.coroutineContext.job.join()
}

private suspend fun lifecycleHome(): Path =
    Path(SystemTemporaryDirectory, "kodex-home-lifecycle-${Random.nextLong()}").also { home ->
        SystemCoroutineFileSystem.createDirectories(home)
        SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"$CurrentKodexApplicationVersion\"")
    }

private suspend fun deleteLifecycleHome(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { deleteLifecycleHome(it) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
