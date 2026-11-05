package io.github.stream29.kodex.utils.sqlite

import kotlin.test.*
import de.infix.testBalloon.framework.core.testSuite
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path

class SqliteTest {
    fun controlledEngineAndTypedStatements() = database { db ->
        assertEquals("3.54.1", sqlite3Version())
        assertEquals(1L, db.query("PRAGMA foreign_keys").single().single())
        assertEquals(2L, db.query("PRAGMA synchronous").single().single())
        assertEquals(1000L, db.query("PRAGMA busy_timeout").single().single())
        val options = db.query("PRAGMA compile_options").flatten()
        assertTrue("THREADSAFE=1" in options)
        assertTrue("OMIT_LOAD_EXTENSION" in options)
        val row = db.query("SELECT ?, ?, ?, ?, ?, ?, ?", listOf(Long.MIN_VALUE, 1.25, "a\u0000中", byteArrayOf(0, -1), null, byteArrayOf(), "")).single()
        assertEquals(Long.MIN_VALUE, row[0]); assertEquals(1.25, row[1]); assertEquals("a\u0000中", row[2])
        assertContentEquals(byteArrayOf(0, -1), row[3] as ByteArray)
        assertNull(row[4]); assertContentEquals(byteArrayOf(), row[5] as ByteArray); assertEquals("", row[6])
        val statement = db.prepare("SELECT ?")
        try {
            statement.bind(1, "first"); assertTrue(statement.step()); assertEquals("first", statement.columns()[0])
            statement.reset(); statement.bind(1, 7L); assertTrue(statement.step()); assertEquals(7L, statement.columns()[0])
        } finally { statement.close() }
    }

    fun transactionRollbackAndNesting() = database { db ->
        db.execute("CREATE TABLE t(x INTEGER UNIQUE)")
        db.transaction { db.execute("INSERT INTO t VALUES(?)", listOf(1L)) }
        val primary = IllegalStateException("synthetic transaction failure")
        assertSame(primary, assertFailsWith<IllegalStateException> { db.transaction { db.execute("INSERT INTO t VALUES(2)"); throw primary } })
        assertEquals(listOf(listOf(1L)), db.query("SELECT x FROM t"))
        assertFailsWith<IllegalStateException> { db.transaction { db.transaction {} } }
        assertEquals(1L, db.query("SELECT count(*) FROM t").single().single())
    }

    fun constraintAbortPreservesStepFailureAndStatementCanBeReused() = database { db ->
        db.execute("CREATE TABLE t(x INTEGER UNIQUE)")
        db.execute("INSERT INTO t VALUES(1)")
        val statement = db.prepare("INSERT INTO t VALUES(?)")
        try {
            statement.bind(1, 1L)
            val primary = assertFailsWith<SqliteFailure> { statement.step() }
            assertEquals("step", primary.operation)
            assertEquals(19, primary.code and 255)
            statement.reset()
            statement.bind(1, 2L)
            assertFalse(statement.step())
            assertEquals(2L, db.query("SELECT count(*) FROM t").single().single())
        } finally { statement.close() }
    }

    fun authorizerReplacementAndFailureCleanup() = database { db ->
        db.execute("CREATE TABLE t(x)")
        var calls = 0
        db.setAuthorizer { action, _, _, _, _ -> calls++; action != 9 /* SQLITE_DELETE */ }
        db.execute("INSERT INTO t VALUES(1)")
        assertEquals(1L, db.query("SELECT x FROM t").single().single())
        assertEquals(23, assertFailsWith<SqliteFailure> { db.execute("DELETE FROM t") }.code)
        assertTrue(calls > 0)
        db.setAuthorizer { _, _, _, _, _ -> false }
        assertFailsWith<SqliteFailure> { db.query("SELECT 1") }
        db.setAuthorizer(null)
        db.execute("DELETE FROM t")
        db.setAuthorizer { _, _, _, _, _ -> error("callback failure") }
        assertEquals(23, assertFailsWith<SqliteFailure> { db.query("SELECT 1") }.code)
        db.setAuthorizer(null)
    }

    fun progressInterruptAndPrimaryExceptionPreservation() = database { db ->
        var calls = 0
        db.setProgressHandler(10) { ++calls >= 10 }
        val stmt = db.prepare(longQuery)
        val primary = assertFailsWith<SqliteFailure> { stmt.step() }
        assertEquals("step", primary.operation); assertEquals(9, primary.code)
        stmt.reset() // sqlite3_reset repeats SQLITE_INTERRUPT; must not replace primary.
        stmt.close(); stmt.close()
        assertEquals("step", primary.operation)
        assertTrue(calls >= 10)
        db.setProgressHandler(10) { true }
        val unreset = db.prepare(longQuery)
        assertEquals(9, assertFailsWith<SqliteFailure> { unreset.step() }.code)
        unreset.close() // finalize also repeats the step error.
        db.setProgressHandler(10) { db.interrupt(); false }
        assertEquals(9, assertFailsWith<SqliteFailure> { db.query(longQuery) }.code)
        db.setProgressHandler(shouldAbort = null)
        assertEquals(42L, db.query("SELECT 42").single().single())
        db.setProgressHandler(10) { error("synthetic callback failure") }
        assertEquals(9, assertFailsWith<SqliteFailure> { db.query(longQuery) }.code)
        db.setProgressHandler(shouldAbort = null)
    }

    fun rawFailuresAndSqliterLogger() {
        val logs = mutableListOf<String>()
        val secret = "SYNTHETIC_DETAIL_4a17"
        openInMemorySqliteConnection(diagnostic = logs::add).use { db ->
            db.execute("CREATE TABLE t(secret TEXT UNIQUE)")
            db.execute("INSERT INTO t VALUES(?)", listOf(secret))
            val missingTable = assertFailsWith<SqliteFailure> { db.query("SELECT '$secret' FROM missing_$secret") }
            assertEquals("prepare", missingTable.operation)
            assertTrue(missingTable.sqliteMessage.orEmpty().contains("no such table: missing_$secret"))
            assertTrue(missingTable.message.orEmpty().contains("no such table: missing_$secret"))
            val uniqueViolation = assertFailsWith<SqliteFailure> { db.execute("INSERT INTO t VALUES(?)", listOf(secret)) }
            assertEquals("step", uniqueViolation.operation)
            assertTrue(uniqueViolation.sqliteMessage.orEmpty().contains("UNIQUE constraint failed: t.secret"))
            assertTrue(uniqueViolation.message.orEmpty().contains("UNIQUE constraint failed: t.secret"))
        }
        // Opening a non-existent parent exercises SQLiter's own error logger before our C wrapper exists.
        val openFailure = assertFailsWith<SqliteFailure> {
            openSqliteConnection(Path("/nonexistent-kodex-$secret/db.sqlite"), diagnostic = logs::add)
        }
        assertEquals("open", openFailure.operation)
        assertNotNull(openFailure.cause)
        assertFalse(openFailure.sqliteMessage.isNullOrBlank())
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.any { it.contains(secret) || it.contains("unable to open database file") })
    }

    fun walReadCancellationDoesNotAffectWriter() {
        val path = Path("kodex-sqlite-${Random.nextLong().toULong()}.db")
        try {
            openSqliteConnection(path).use { writer ->
                assertEquals("wal", writer.query("PRAGMA journal_mode").single().single())
                writer.execute("CREATE TABLE t(x)")
                openSqliteConnection(path, readOnly = true).use { read ->
                    assertEquals(1L, read.query("PRAGMA query_only").single().single())
                    assertEquals(1L, read.query("PRAGMA foreign_keys").single().single())
                    assertFailsWith<SqliteFailure> { read.execute("INSERT INTO t VALUES(9)") }
                    writer.transaction {
                        writer.execute("INSERT INTO t VALUES(1)")
                        read.setProgressHandler(10) { read.interrupt(); false }
                        assertEquals(9, assertFailsWith<SqliteFailure> { read.query(longQuery) }.code)
                        read.setProgressHandler(shouldAbort = null)
                        assertEquals(0L, read.query("SELECT count(*) FROM t").single().single())
                        writer.execute("INSERT INTO t VALUES(2)")
                    }
                    assertEquals(2L, read.query("SELECT count(*) FROM t").single().single())
                }
            }
        } finally {
            deleteSqliteTestFiles(path)
        }
    }

    private fun database(block: (SqliteConnection) -> Unit) =
        openInMemorySqliteConnection().use(block)
}

internal const val longQuery: String = "WITH RECURSIVE n(x) AS (VALUES(0) UNION ALL SELECT x+1 FROM n WHERE x<1000000000) SELECT sum(x) FROM n"
internal expect fun deleteSqliteTestFiles(path: Path)

val sqliteDriverTest by testSuite {
    test("constraint abort primary and reuse") { SqliteTest().constraintAbortPreservesStepFailureAndStatementCanBeReused() }
    test("controlledEngineAndTypedStatements") { SqliteTest().controlledEngineAndTypedStatements() }
    test("transactionRollbackAndNesting") { SqliteTest().transactionRollbackAndNesting() }
    test("authorizerReplacementAndFailureCleanup") { SqliteTest().authorizerReplacementAndFailureCleanup() }
    test("progressInterruptAndPrimaryExceptionPreservation") { SqliteTest().progressInterruptAndPrimaryExceptionPreservation() }
    test("rawFailuresAndSqliterLogger") { SqliteTest().rawFailuresAndSqliterLogger() }
    test("walReadCancellationDoesNotAffectWriter") { SqliteTest().walReadCancellationDoesNotAffectWriter() }
}

val sqliteConcurrentCancellationTest by testSuite {
    test("cross thread interrupt finishes before reader reuse and leaves writer commits intact") {
        runTest {
            withContext(Dispatchers.Default) { withTimeout(30_000) {
                val path = Path("kodex-interrupt-${Random.nextLong().toULong()}.db")
                try {
                    openSqliteConnection(path).use { writer ->
                        writer.execute("CREATE TABLE t(x)")
                        openSqliteConnection(path, readOnly = true).use { read ->
                            repeat(10) { index ->
                                val started = CompletableDeferred<Unit>()
                                read.setProgressHandler(100) { started.complete(Unit); false }
                                val pending = async(Dispatchers.Default) {
                                    assertFailsWith<SqliteFailure> { read.query(longQuery) }
                                }
                                val primary = try {
                                    started.await()
                                    writer.transaction {
                                        writer.execute("INSERT INTO t VALUES(?)", listOf(index.toLong()))
                                        read.interrupt()
                                    }
                                    pending.await()
                                } finally {
                                    read.interrupt()
                                    withContext(NonCancellable) { pending.join() }
                                }
                                assertEquals(9, primary.code)
                                read.setProgressHandler(shouldAbort = null)
                                assertEquals((index + 1).toLong(), read.query("SELECT count(*) FROM t").single().single())
                            }
                        }
                    }
                } finally { deleteSqliteTestFiles(path) }
            } }
        }
    }
    test("close cleans active statements and callbacks exactly once") {
        val db = openInMemorySqliteConnection()
        db.setAuthorizer { _, _, _, _, _ -> true }
        db.setProgressHandler(10) { false }
        val statement = db.prepare("SELECT 1 UNION ALL SELECT 2")
        assertTrue(statement.step())
        db.close(); db.close(); statement.close()
        assertFailsWith<IllegalStateException> { statement.step() }
        assertFailsWith<IllegalStateException> { db.prepare("SELECT 1") }
    }
}
