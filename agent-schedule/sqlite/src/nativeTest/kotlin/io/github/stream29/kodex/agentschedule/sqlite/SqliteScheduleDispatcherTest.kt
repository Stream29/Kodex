@file:OptIn(
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlin.native.concurrent.ObsoleteWorkersApi::class,
    kotlin.time.ExperimentalTime::class,
)

package io.github.stream29.kodex.agentschedule.sqlite

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.sqlite.initializeKodexHomeDatabase
import io.github.stream29.kodex.utils.sqlite.openInMemorySqliteConnection
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.native.concurrent.Worker
import kotlin.test.assertEquals
import kotlin.time.Instant

val sqliteScheduleDispatcherTest by testSuite {
    test("schedule initialization, reads and writes use the injected connection thread") {
        newSingleThreadContext("SQLite.schedule.test").use { dispatcher ->
            val (thread, connection) = withContext(dispatcher) {
                Worker.current.id to openInMemorySqliteConnection().also(::initializeKodexHomeDatabase)
            }
            try {
                var checkedAccesses = 0
                val repository = SqliteScheduleRepository(connection, Mutex(), dispatcher) {
                    assertEquals(thread, Worker.current.id)
                    checkedAccesses++
                }
                val now = Instant.fromEpochSeconds(100)
                val scheduled = repository.create(7, now, null, "synthetic deadline", now)
                assertEquals(listOf(scheduled), repository.list(7))
                assertEquals(3, checkedAccesses)
            } finally {
                withContext(NonCancellable) { withContext(dispatcher) { connection.close() } }
            }
        }
    }
}
