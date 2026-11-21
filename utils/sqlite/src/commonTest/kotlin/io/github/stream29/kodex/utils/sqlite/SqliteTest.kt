package io.github.stream29.kodex.utils.sqlite

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.*

class SqliteTest {
    fun controlledEngineAndTypedStatements() = database { db ->
        assertEquals("3.54.1", db.query("SELECT sqlite_version()").single().single())
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

    fun transactionRollbackNestingAndChanges() = database { db ->
        db.execute("CREATE TABLE t(x INTEGER UNIQUE)")
        db.transaction {
            db.execute("INSERT INTO t VALUES(?)", listOf(1L))
            assertEquals(1, db.changes())
        }
        db.execute("UPDATE t SET x = 10 WHERE x = 999")
        assertEquals(0, db.changes())
        db.execute("UPDATE t SET x = 10 WHERE x = 1")
        assertEquals(1, db.changes())
        val primary = IllegalStateException("synthetic transaction failure")
        assertSame(primary, assertFailsWith<IllegalStateException> { db.transaction { db.execute("INSERT INTO t VALUES(2)"); throw primary } })
        assertEquals(listOf(listOf(10L)), db.query("SELECT x FROM t"))
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

    fun rawFailuresPreserveSqliteDetail() {
        val secret = "SYNTHETIC_DETAIL_4a17"
        openInMemorySqliteConnection().use { db ->
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
        val openFailure = assertFailsWith<SqliteFailure> {
            openSqliteConnection(Path("/nonexistent-kodex-$secret/db.sqlite"))
        }
        assertEquals("open", openFailure.operation)
        assertNotNull(openFailure.cause)
        assertFalse(openFailure.sqliteMessage.isNullOrBlank())
    }

    fun walReadOnlyConnectionIsolation() {
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

internal expect fun deleteSqliteTestFiles(path: Path)

val sqliteDriverTest by testSuite {
    test("constraint abort primary and reuse") { SqliteTest().constraintAbortPreservesStepFailureAndStatementCanBeReused() }
    test("controlledEngineAndTypedStatements") { SqliteTest().controlledEngineAndTypedStatements() }
    test("transactionRollbackNestingAndChanges") { SqliteTest().transactionRollbackNestingAndChanges() }
    test("rawFailuresPreserveSqliteDetail") { SqliteTest().rawFailuresPreserveSqliteDetail() }
    test("walReadOnlyConnectionIsolation") { SqliteTest().walReadOnlyConnectionIsolation() }
    test("existing-only open never creates missing files or changes the journal mode") {
        val path = Path(SystemTemporaryDirectory, "kodex-sqlite-existing-${Random.nextLong().toULong()}.db")
        try {
            assertFailsWith<SqliteFailure> { openExistingSqliteConnection(path).close() }
            assertFalse(SystemFileSystem.exists(path))
            openSqliteConnection(path).use { db ->
                assertEquals(listOf(listOf("delete")), db.query("PRAGMA journal_mode=DELETE"))
                db.execute("CREATE TABLE t(x)")
            }
            openExistingSqliteConnection(path).use { db ->
                assertEquals(listOf(listOf("delete")), db.query("PRAGMA journal_mode"))
                assertEquals(listOf(listOf(1L)), db.query("PRAGMA foreign_keys"))
                db.execute("INSERT INTO t VALUES(1)")
                assertEquals(listOf(listOf(1L)), db.query("SELECT x FROM t"))
            }
        } finally {
            deleteSqliteTestFiles(path)
        }
    }
    test("close cleans active statements idempotently") {
        val db = openInMemorySqliteConnection()
        val statement = db.prepare("SELECT 1 UNION ALL SELECT 2")
        assertTrue(statement.step())
        db.close(); db.close(); statement.close()
        assertFailsWith<IllegalStateException> { statement.step() }
        assertFailsWith<IllegalStateException> { db.prepare("SELECT 1") }
        assertFailsWith<IllegalStateException> { db.changes() }
    }
}
