@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.utils.sqlite

import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.NO_VERSION_CHECK
import co.touchlab.sqliter.SynchronousFlag
import co.touchlab.sqliter.createDatabaseManager
import co.touchlab.sqliter.sqlite3.*
import kotlinx.cinterop.*
import kotlinx.io.files.Path

public actual fun openSqliteConnection(
    path: Path,
    readOnly: Boolean,
): SqliteConnection = openConnection(path = path.toString(), inMemory = false, readOnly = readOnly)

public actual fun openInMemorySqliteConnection(): SqliteConnection =
    openConnection(path = null, inMemory = true, readOnly = false)

public actual fun openExistingSqliteConnection(path: Path): SqliteConnection = memScoped {
    val name = path.toString()
    require('\u0000' !in name) { "SQLite paths cannot contain NUL." }
    val database = alloc<CPointerVar<sqlite3>>()
    val code = sqlite3_open_v2(name, database.ptr, SQLITE_OPEN_READWRITE, null)
    val pointer = database.value
    if (code != SQLITE_OK || pointer == null) {
        val failure = sqliteFailure("open existing", code, pointer)
        pointer?.let { sqlite3_close(it) }
        throw failure
    }
    val result = NativeConnection(pointer) {
        val closeCode = sqlite3_close(pointer)
        if (closeCode != SQLITE_OK) throw sqliteFailure("close", closeCode, pointer)
    }
    try {
        val timeout = sqlite3_busy_timeout(pointer, 1000)
        if (timeout != SQLITE_OK) throw sqliteFailure("busy timeout", timeout, pointer)
        result.execute("PRAGMA foreign_keys=ON")
        result
    } catch (primary: Throwable) {
        try { result.close() } catch (cleanup: Throwable) { primary.addSuppressed(cleanup) }
        throw primary
    }
}

private fun openConnection(
    path: String?,
    inMemory: Boolean,
    readOnly: Boolean,
): SqliteConnection {
    val normalized = path?.replace('\\', '/')
    val split = normalized?.lastIndexOf('/') ?: -1
    val configuration = DatabaseConfiguration(
        name = if (inMemory) null else normalized!!.substring(split + 1),
        version = NO_VERSION_CHECK,
        create = {},
        inMemory = inMemory,
        extendedConfig = DatabaseConfiguration.Extended(
            basePath = if (split >= 0) normalized!!.substring(0, split).ifEmpty { "/" } else ".",
            foreignKeyConstraints = true,
            busyTimeout = 1000,
            synchronousFlag = SynchronousFlag.FULL,
        ),
    )
    val connection = try {
        createDatabaseManager(configuration).createSingleThreadedConnection()
    } catch (cause: Throwable) {
        throw sqliteFailure("open", SQLITE_CANTOPEN, cause = cause)
    }
    val result = NativeConnection(connection.getDbPointer()) {
        try { connection.close() } catch (cause: Throwable) {
            throw sqliteFailure("close", SQLITE_ERROR, cause = cause)
        }
    }
    try {
        if (readOnly) result.execute("PRAGMA query_only=ON")
        result.execute("PRAGMA foreign_keys=ON")
        return result
    } catch (primary: Throwable) {
        runCatching { result.close() }
        throw primary
    }
}

private fun sqliteFailure(
    operation: String,
    code: Int,
    db: CPointer<sqlite3>? = null,
    cause: Throwable? = null,
): SqliteFailure {
    val dbMessage = db?.let { sqlite3_errmsg(it)?.toKString() }?.takeIf { it.isNotBlank() && it != "not an error" }
    val codeMessage = sqlite3_errstr(code)?.toKString()?.takeIf { it.isNotBlank() && it != "not an error" }
    val causeMessage = cause?.message?.takeIf { it.isNotBlank() }
    return SqliteFailure(
        operation = operation,
        code = code,
        sqliteMessage = dbMessage ?: causeMessage ?: codeMessage,
        cause = cause,
    )
}

private class NativeConnection(
    private val pointer: CPointer<sqlite3>,
    private val closeHandle: () -> Unit,
) : SqliteConnection {
    private val statements = mutableSetOf<NativeStatement>()
    private var closed = false
    private var inTransaction = false

    override fun prepare(sql: String): SqliteStatement {
        check(!closed) { "SQLite connection closed" }
        return memScoped {
            val statement = alloc<CPointerVar<sqlite3_stmt>>()
            val bytes = sql.cstr
            val code = sqlite3_prepare_v2(pointer, bytes.ptr, -1, statement.ptr, null)
            if (code != SQLITE_OK) {
                val failure = sqliteFailure("prepare", code, pointer)
                statement.value?.let { sqlite3_finalize(it) }
                throw failure
            }
            val handle = statement.value ?: throw sqliteFailure("prepare", SQLITE_MISUSE, pointer)
            NativeStatement(pointer, handle) { statements.remove(it) }.also { statements.add(it) }
        }
    }

    private fun <T> withStatement(sql: String, parameters: List<Any?>, block: (SqliteStatement) -> T): T {
        val statement = prepare(sql)
        var primary: Throwable? = null
        try {
            parameters.forEachIndexed { index, value -> statement.bind(index + 1, value) }
            return block(statement)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try { statement.close() } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }

    override fun execute(sql: String, parameters: List<Any?>) {
        withStatement(sql, parameters) { while (it.step()) { /* Drain RETURNING and pragmas. */ } }
    }

    override fun query(sql: String, parameters: List<Any?>): List<List<Any?>> =
        withStatement(sql, parameters) { statement ->
            buildList { while (statement.step()) add(statement.columns()) }
        }

    override fun changes(): Int {
        check(!closed) { "SQLite connection closed" }
        return sqlite3_changes(pointer)
    }

    override fun <T> transaction(block: () -> T): T {
        check(!inTransaction) { "Nested SQLite transaction" }
        execute("BEGIN IMMEDIATE")
        inTransaction = true
        try {
            val result = block()
            execute("COMMIT")
            return result
        } catch (primary: Throwable) {
            try { execute("ROLLBACK") } catch (cleanup: Throwable) { primary.addSuppressed(cleanup) }
            throw primary
        } finally { inTransaction = false }
    }

    override fun close() {
        if (closed) return
        var primary: Throwable? = null
        statements.toList().forEach { statement ->
            try { statement.close() } catch (failure: Throwable) {
                if (primary == null) primary = failure else primary.addSuppressed(failure)
            }
        }
        closed = true
        try { closeHandle() } catch (failure: Throwable) {
            if (primary == null) primary = failure else primary.addSuppressed(failure)
        }
        primary?.let { throw it }
    }
}

private class NativeStatement(
    private val dbPointer: CPointer<sqlite3>,
    private val pointer: CPointer<sqlite3_stmt>,
    private val onClose: (NativeStatement) -> Unit,
) : SqliteStatement {
    private var closed = false
    private var failure: SqliteFailure? = null
    private var hasRow = false
    override fun bind(index: Int, value: Any?) {
        check(!closed)
        val code = when (value) {
            null -> sqlite3_bind_null(pointer, index)
            is Long -> sqlite3_bind_int64(pointer, index, value)
            is Double -> sqlite3_bind_double(pointer, index, value)
            is String -> memScoped {
                val bytes = value.encodeToByteArray()
                sqlite3_bind_text(pointer, index, value, bytes.size, SQLITE_TRANSIENT)
            }
            is ByteArray -> if (value.isEmpty()) sqlite3_bind_zeroblob(pointer, index, 0) else value.usePinned {
                sqlite3_bind_blob(pointer, index, it.addressOf(0), value.size, SQLITE_TRANSIENT)
            }
            else -> throw IllegalArgumentException("Unsupported SQLite parameter type")
        }
        if (code != SQLITE_OK) throw sqliteFailure("bind", code, dbPointer)
    }

    override fun step(): Boolean {
        check(!closed)
        val code = sqlite3_step(pointer)
        hasRow = code == SQLITE_ROW
        if (code != SQLITE_ROW && code != SQLITE_DONE) {
            val primary = sqliteFailure("step", code, dbPointer)
            failure = primary
            throw primary
        }
        return hasRow
    }

    override fun columns(): List<Any?> {
        check(!closed && hasRow)
        return List(sqlite3_column_count(pointer)) { index ->
            when (sqlite3_column_type(pointer, index)) {
                SQLITE_NULL -> null
                SQLITE_INTEGER -> sqlite3_column_int64(pointer, index)
                SQLITE_FLOAT -> sqlite3_column_double(pointer, index)
                SQLITE_TEXT -> sqlite3_column_text(pointer, index)!!.reinterpret<ByteVar>().readBytes(sqlite3_column_bytes(pointer, index)).decodeToString()
                SQLITE_BLOB -> sqlite3_column_blob(pointer, index)?.reinterpret<ByteVar>()?.readBytes(sqlite3_column_bytes(pointer, index)) ?: byteArrayOf()
                else -> error("Unknown SQLite column type")
            }
        }
    }

    override fun reset() {
        check(!closed)
        val code = sqlite3_reset(pointer)
        hasRow = false
        val primary = failure
        failure = null
        if (code != SQLITE_OK && primary == null) throw sqliteFailure("reset", code, dbPointer)
        val clear = sqlite3_clear_bindings(pointer)
        if (clear != SQLITE_OK) throw sqliteFailure("clear bindings", clear, dbPointer)
    }

    override fun close() {
        if (closed) return
        val code = sqlite3_finalize(pointer)
        closed = true
        onClose(this)
        if (code != SQLITE_OK && failure == null) throw sqliteFailure("finalize", code, dbPointer)
    }
}
