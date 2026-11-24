@file:OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)

package io.github.stream29.kodex.utils.sqlite

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.native.concurrent.Worker
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
val sqliteReadDispatcherTest by testSuite {
    test("read-only SQLite connections and row decoding use the same dedicated thread") {
        val path = Path(SystemTemporaryDirectory, "kodex-sqlite-read-${Uuid.random()}.db")
        try {
            openSqliteConnection(path).use { db ->
                db.execute("PRAGMA user_version=1")
                db.execute("CREATE TABLE fixture(number INTEGER)")
                db.execute("INSERT INTO fixture VALUES(7)")
            }
            val policy = SqliteReadPolicy(
                relations = setOf("fixture"),
                functions = emptySet(),
                requiredUserVersion = 1,
                inputBytes = 1024,
                cellBytes = 1024,
            )
            val callerThread = Worker.current.id
            val workers = coroutineScope {
                List(12) {
                    async(Dispatchers.Default) {
                        withSqliteReadQuery(
                            path, "SELECT number FROM fixture", policy, TimeSource.Monotonic.markNow() + 10.seconds,
                        ) { rows ->
                            assertTrue(rows.next())
                            assertEquals(7L, rows.cell(0))
                            Worker.current.id
                        }
                    }
                }.awaitAll().toSet()
            }
            assertEquals(1, workers.size)
            assertNotEquals(callerThread, workers.single())
        } finally {
            deleteSqliteTestFiles(path)
        }
    }
}
