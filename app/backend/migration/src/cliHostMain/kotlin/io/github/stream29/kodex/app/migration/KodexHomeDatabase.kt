package io.github.stream29.kodex.app.migration

import io.github.stream29.kodex.agentstorage.sqlite.KodexHomeDatabaseApplicationId
import io.github.stream29.kodex.agentstorage.sqlite.initializeKodexHomeDatabase
import io.github.stream29.kodex.agentstorage.sqlite.requireEmptyKodexHomeDatabase
import io.github.stream29.kodex.agentstorage.sqlite.requireKodexHomeDatabase
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.openExistingSqliteConnection
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.readByteArray

internal suspend fun requireHomeDatabaseHeader(
    home: Path, fileSystem: CoroutineFileSystem, requireStandalone: Boolean = false,
) {
    val database = Path(home, HomeDatabaseFile)
    val metadata = fileSystem.metadataOrNull(database)
    if (metadata?.isRegularFile != true || metadata.size < 512 || !fileSystem.isDirectEntry(database)) {
        throw incompatibleHome(home, "Missing or invalid SQLite database: $database")
    }
    val header = fileSystem.useSource(database) { source ->
        val buffer = Buffer()
        while (buffer.size < SqliteHeaderSize) {
            val count = source.readAtMostTo(buffer, SqliteHeaderSize - buffer.size)
            if (count < 0) break
        }
        buffer.readByteArray()
    }
    if (header.size.toLong() != SqliteHeaderSize ||
        !header.copyOfRange(0, SqliteHeaderMagic.size).contentEquals(SqliteHeaderMagic)
    ) {
        throw incompatibleHome(home, "Invalid SQLite header: $database")
    }
    val encodedPageSize = header[16].unsigned() * 256 + header[17].unsigned()
    val pageSize = if (encodedPageSize == 1) 65536 else encodedPageSize
    val applicationId = (68..71).fold(0) { id, byte -> (id shl 8) or header[byte].unsigned() }
    if (pageSize !in 512..65536 || (pageSize and (pageSize - 1)) != 0 || metadata.size % pageSize != 0L ||
        header[18].unsigned() !in 1..2 || header[19].unsigned() !in 1..2 ||
        applicationId != KodexHomeDatabaseApplicationId
    ) {
        throw incompatibleHome(home, "SQLite database is corrupt or does not belong to this Home generation: $database")
    }
    if (requireStandalone && (header[18].unsigned() != 1 || header[19].unsigned() != 1)) {
        throw incompatibleHome(home, "The database published during initialization must not use WAL mode: $database")
    }
}

internal suspend fun requireHomeDatabaseSchema(home: Path, requireEmpty: Boolean = false) {
    // Only after checking the physical Home marker and header, and only with a
    // Home lease. This connection never creates a DB, but can perform SQLite's
    // normal locking/recovery (including WAL sidecars). Never use it in preflight.
    inspectHomeRead(home, "Unable to validate the Kodex Home database") {
        withContext(Dispatchers.IO) {
            openExistingSqliteConnection(Path(home, HomeDatabaseFile)).use { connection ->
                if (requireEmpty) requireEmptyKodexHomeDatabase(connection)
                else requireKodexHomeDatabase(connection)
            }
        }
    }
}

internal suspend fun initializeHomeDatabase(
    home: Path,
    fileSystem: CoroutineFileSystem,
) {
    // Only ever called under the write lease once the initializing marker is
    // published and the layout proves that no final database is present.
    for (name in StagingDatabaseFiles) fileSystem.delete(Path(home, name), mustExist = false)
    val staging = Path(home, StagingDatabaseFile)
    withContext(Dispatchers.IO) {
        openSqliteConnection(staging).use { connection ->
            // A published database must be self-contained. Never rename it while
            // its committed schema could still be present solely in a sidecar.
            check(connection.query("PRAGMA journal_mode=DELETE") == listOf(listOf("delete")))
            connection.execute("PRAGMA synchronous=FULL")
            initializeKodexHomeDatabase(connection)
            requireEmptyKodexHomeDatabase(connection)
        }
    }
    check(StagingDatabaseFiles.drop(1).none { fileSystem.exists(Path(home, it)) }) {
        "SQLite left sidecars after closing the staging database: $staging"
    }
    val published = Path(home, HomeDatabaseFile)
    if (fileSystem.metadataOrNull(published) != null || published in fileSystem.list(home)) {
        throw incompatibleHome(home, "A database appeared while initializing: $published")
    }
    fileSystem.atomicMove(staging, published)
}

private fun Byte.unsigned(): Int = toInt() and 0xff

internal const val HomeDatabaseFile: String = "kodex.db"
private const val StagingDatabaseFile: String = ".kodex.db.initializing"
internal val StagingDatabaseFiles: List<String> = listOf(
    StagingDatabaseFile, "$StagingDatabaseFile-journal", "$StagingDatabaseFile-wal", "$StagingDatabaseFile-shm",
)
private const val SqliteHeaderSize: Long = 100
private val SqliteHeaderMagic = "SQLite format 3\u0000".encodeToByteArray()
