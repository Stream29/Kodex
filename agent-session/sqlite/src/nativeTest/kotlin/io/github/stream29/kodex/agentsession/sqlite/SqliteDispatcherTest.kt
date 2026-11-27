@file:OptIn(
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlin.native.concurrent.ObsoleteWorkersApi::class,
)

package io.github.stream29.kodex.agentsession.sqlite

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.sqlite.initializeKodexHomeDatabase
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.mq.InMemoryMqBroker
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import io.github.stream29.kodex.utils.sqlite.openInMemorySqliteConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.native.concurrent.Worker
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
val sqliteDispatcherTest by testSuite {
    test("cancellation after SQLite open closes the rejected connection on its opening thread") {
        newSingleThreadContext("SQLite.test").use { dispatcher ->
            val callerThread = Worker.current.id
            val openedOn = CompletableDeferred<Int>()
            val closedOn = CompletableDeferred<Int>()
            val observed = CompletableDeferred<CancellationException>()
            val cleanupFailure = IllegalStateException("synthetic close failure")
            coroutineScope {
                launch {
                    val operation = currentCoroutineContext().job
                    val failure = assertFailsWith<CancellationException> {
                        acquireDatabaseConnection(dispatcher) {
                            val real = openInMemorySqliteConnection()
                            openedOn.complete(Worker.current.id)
                            operation.cancel(CancellationException("SQLite handoff cancelled"))
                            object : SqliteConnection by real {
                                override fun close() {
                                    real.close()
                                    assertTrue(closedOn.complete(Worker.current.id), "Connection closed twice")
                                    throw cleanupFailure
                                }
                            }
                        }
                    }
                    observed.complete(failure)
                }.join()
            }
            assertTrue(openedOn.isCompleted)
            assertTrue(closedOn.isCompleted, "Operation joined before its rejected connection was closed")
            assertTrue(observed.isCompleted)
            assertNotEquals(callerThread, openedOn.await())
            assertEquals(openedOn.await(), closedOn.await())
            assertContains(observed.await().message.orEmpty(), "SQLite handoff cancelled")
            assertTrue(generateSequence<Throwable>(observed.await()) { it.cause }
                .flatMap { it.suppressedExceptions.asSequence() }.any { it === cleanupFailure })
        }
    }

    test("borrowed session and agent storage share the caller's SQLite thread") {
        val fs = SystemCoroutineFileSystem
        val root = Path(SystemTemporaryDirectory, "kodex-sqlite-dispatcher-${Uuid.random()}")
        try {
            fs.createDirectories(root)
            newSingleThreadContext("SQLite.test").use { dispatcher ->
                val (thread, db) = withContext(dispatcher) {
                    Worker.current.id to openInMemorySqliteConnection().also(::initializeKodexHomeDatabase)
                }
                val connection = object : SqliteConnection by db {
                    override fun query(sql: String, parameters: List<Any?>): List<List<Any?>> {
                        assertEquals(thread, Worker.current.id)
                        return db.query(sql, parameters)
                    }

                    override fun execute(sql: String, parameters: List<Any?>) {
                        assertEquals(thread, Worker.current.id)
                        db.execute(sql, parameters)
                    }

                    override fun <T> transaction(block: () -> T): T {
                        assertEquals(thread, Worker.current.id)
                        return db.transaction(block)
                    }
                }
                try {
                    coroutineScope {
                        testKodexAgentDependencies().use { dependencies ->
                            val repository = SqliteKodexSessionRepository(
                                root, connection, Mutex(), dependencies, InMemoryMqBroker(), dispatcher = dispatcher,
                            )
                            try {
                                val session = repository.open(repository.create())
                                assertEquals(-1, session.storage.latestIndex())
                            } finally {
                                repository.closeAndJoin()
                            }
                            // Borrowed repository shutdown leaves the owner's lane and connection usable.
                            assertEquals(listOf(listOf(1L)), withContext(dispatcher) { connection.query("SELECT 1") })
                        }
                    }
                } finally {
                    withContext(NonCancellable) { withContext(dispatcher) { db.close() } }
                }
            }
        } finally {
            withContext(NonCancellable) { deleteDispatcherFixture(root) }
        }
    }
}

private suspend fun deleteDispatcherFixture(path: Path) {
    val fs = SystemCoroutineFileSystem
    if (fs.metadataOrNull(path)?.isDirectory == true) {
        fs.list(path).forEach { deleteDispatcherFixture(it) }
    }
    fs.delete(path, mustExist = false)
}
