package io.github.stream29.kodex.app.migration.v0_7_3

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.sqlite.SqliteKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.sqlite.initializeSqlitePhaseOneSchema
import io.github.stream29.kodex.app.migration.KodexHomeMigrations
import io.github.stream29.kodex.app.migration.MigrationVersion
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.migration.v0_7_2.migrateToV0_7_2
import io.github.stream29.kodex.utils.coroutines.use
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import io.github.stream29.kodex.utils.sqlite.SqliteFailure
import io.github.stream29.kodex.utils.sqlite.openInMemorySqliteConnection
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

private val fs = SystemCoroutineFileSystem
private val target = MigrationVersion("0.7.3")
private const val FullCatalog = "SELECT * FROM session_catalog ORDER BY session_id"

val migrateToV0_7_3Test by testSuite {
    testFixture {
        val home = Path(SystemTemporaryDirectory, "catalog-migration-${Random.nextLong().toULong()}")
        fs.createDirectories(home, mustCreate = true)
        home
    } closeWith { deleteTree(this) } asParameterForEach {
        test("schema validation after create rolls back the complete view replacement") { home ->
            migrateToV0_7_2(home, fs)
            fs.writeString(Path(home, "version.json"), "\"0.7.2\"")
            val path = Path(home, "kodex.db")
            val oldView = openSqliteConnection(path).use { db ->
                db.execute("DROP INDEX commits_global_index")
                db.view()
            }
            assertFailsWith<SqliteFailure> { migrateToV0_7_3(home, fs) }
            assertEquals(oldView, openSqliteConnection(path).use { it.view() })
            assertEquals("\"0.7.2\"", fs.readString(Path(home, "version.json")))
        }
        test("post-commit checkpoint failure retries and matches the fresh catalog") { home ->
            migrateToV0_7_2(home, fs)
            val checkpoint = Path(home, "version.json")
            fs.writeString(checkpoint, "\"0.7.2\"")
            val mirror = Path(home, "mirror-sentinel")
            fs.writeString(mirror, "untouched")
            val path = Path(home, "kodex.db")
            val old = openSqliteConnection(path).use { db ->
                db.execute("INSERT INTO sessions VALUES(1, 1)")
                db.execute("INSERT INTO agents VALUES(1, 1, NULL)")
                db.execute("""INSERT INTO agent_commits VALUES(1, 'settings', 0,
                    '{"threadName":"before","cwd":"/fixture"}', '2026-10-01T00:00:00Z')""")
                db.snapshot()
            }
            val noCheckpoint = object : CoroutineFileSystem by fs {
                override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
                    if (path == checkpoint) throw IOException("Simulated failure before the Home checkpoint")
                    fs.writeString(path, content, append, mustCreate)
                }
            }
            assertFailsWith<IOException> { prepareKodexHome(home, target, KodexHomeMigrations, noCheckpoint) }
            assertEquals("\"0.7.2\"", fs.readString(checkpoint))
            val first = openSqliteConnection(path).use { it.snapshot() }
            assertNotEquals(old.view, first.view)
            assertEquals(old.copy(view = first.view), first)
            openInMemorySqliteConnection().use { fresh ->
                initializeSqlitePhaseOneSchema(fresh)
                assertEquals(fresh.view(), first.view)
            }
            prepareKodexHome(home, target, KodexHomeMigrations, fs).closeAndJoin()
            assertEquals("\"0.7.3\"", fs.readString(checkpoint))
            assertEquals(first, openSqliteConnection(path).use { it.snapshot() })
            assertEquals("untouched", fs.readString(mirror))
        }
        test("file-backed long catalog preserves full SQL and repository results") { home ->
            migrateToV0_7_2(home, fs)
            val path = Path(home, "kodex.db")
            openSqliteConnection(path).use(::seedLongCatalog)
            val beforeSql = measureCatalogSql(path, "before")
            val beforeEntries = measureCatalogRepository(home, "before")
            migrateToV0_7_3(home, fs)
            assertEquals(beforeSql, measureCatalogSql(path, "after"))
            assertEquals(beforeEntries, measureCatalogRepository(home, "after"))
            openSqliteConnection(path).use { db ->
                val plan = db.query("EXPLAIN QUERY PLAN $FullCatalog")
                assertTrue(plan.any { "commits_global_index (agent_id=? AND index=?)" in it.last().toString() }, "$plan")
            }
        }
    }
}

private fun SqliteConnection.view(): String =
    query("SELECT sql FROM sqlite_schema WHERE name='session_catalog'").single().single() as String

private data class CatalogSnapshot(
    val view: String,
    val remainingSchema: List<List<Any?>>,
    val userVersion: List<List<Any?>>,
    val history: List<List<Any?>>,
    val catalog: List<List<Any?>>,
)

private fun SqliteConnection.snapshot() = CatalogSnapshot(
    view(),
    query("SELECT name, sql FROM sqlite_schema WHERE name <> 'session_catalog' ORDER BY name"),
    query("PRAGMA user_version"),
    query("SELECT * FROM agent_commits ORDER BY agent_id, type, \"index\""),
    query(FullCatalog),
)

private fun seedLongCatalog(db: SqliteConnection) {
    db.transaction {
        db.execute("""
            WITH RECURSIVE ids(id) AS (VALUES(0) UNION ALL SELECT id + 1 FROM ids WHERE id < 127)
            INSERT INTO sessions SELECT id, id % 2 FROM ids
        """.trimIndent())
        db.execute("INSERT INTO agents SELECT session_id, session_id, NULL FROM sessions")
        db.execute("""
            WITH RECURSIVE indices(i) AS (VALUES(1) UNION ALL SELECT i + 1 FROM indices WHERE i < 2048)
            INSERT INTO agent_commits
            SELECT agent_id, 'assistant_message', i, '{}', '2026-10-01T00:00:00Z' FROM agents, indices
        """.trimIndent())
        db.execute("""
            INSERT INTO agent_commits
            SELECT agent_id, 'settings', 0, '{"threadName":"first","cwd":"/old"}', '2026-09-01T00:00:00Z'
            FROM agents WHERE agent_id % 8 <> 0
        """.trimIndent())
        db.execute("""
            INSERT INTO agent_commits
            SELECT agent_id, 'settings', 2049, '{"threadName":"latest","cwd":"/fixture"}', '2026-10-02T00:00:00Z'
            FROM agents
        """.trimIndent())
    }
}

private fun measureCatalogSql(path: Path, label: String): List<List<Any?>> =
    openSqliteConnection(path).use { db ->
        val cold = TimeSource.Monotonic.markNow()
        val rows = db.query(FullCatalog)
        val coldElapsed = cold.elapsedNow()
        assertEquals(128, rows.size)
        val warm = TimeSource.Monotonic.markNow()
        repeat(5) { check(db.query(FullCatalog).size == rows.size) }
        println("catalog $label SQL (fresh connection / 5 warm): $coldElapsed / ${warm.elapsedNow()}")
        rows
    }

private suspend fun CoroutineScope.measureCatalogRepository(home: Path, label: String) =
    testKodexAgentDependencies().use { dependencies ->
        SqliteKodexSessionRepository(home, dependencies).use { repository ->
            val cold = TimeSource.Monotonic.markNow()
            val rows = repository.listEntries(includeArchived = true)
            val coldElapsed = cold.elapsedNow()
            assertEquals(128, rows.size)
            assertEquals(64, repository.listEntries(includeArchived = false).size)
            val warm = TimeSource.Monotonic.markNow()
            repeat(5) { check(repository.listEntries(includeArchived = true).size == rows.size) }
            println("catalog $label repository (first list / 5 warm): $coldElapsed / ${warm.elapsedNow()}")
            rows.map { listOf(it.entryIndex, it.archived, it.threadName, it.createdAt, it.lastActivityAt) }
        }
    }

private suspend fun deleteTree(path: Path) {
    val metadata = fs.metadataOrNull(path) ?: return
    if (metadata.isDirectory) fs.list(path).forEach { deleteTree(it) }
    fs.delete(path, mustExist = false)
}
