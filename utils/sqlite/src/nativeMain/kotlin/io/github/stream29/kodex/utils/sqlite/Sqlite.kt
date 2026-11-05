@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.utils.sqlite

import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.DatabaseConnection
import co.touchlab.sqliter.NO_VERSION_CHECK
import co.touchlab.sqliter.SynchronousFlag
import co.touchlab.sqliter.createDatabaseManager
import co.touchlab.sqliter.interop.Logger
import co.touchlab.sqliter.sqlite3.*
import kotlinx.cinterop.*
import kotlinx.io.files.Path

public actual fun sqlite3Version(): String = sqlite3_libversion()!!.toKString()

public actual fun openSqliteConnection(
    path: Path,
    readOnly: Boolean,
    diagnostic: (String) -> Unit,
): SqliteConnection = openConnection(path = path.toString(), inMemory = false, readOnly = readOnly, diagnostic = diagnostic)

public actual fun openInMemorySqliteConnection(
    diagnostic: (String) -> Unit,
): SqliteConnection = openConnection(path = null, inMemory = true, readOnly = false, diagnostic = diagnostic)

private fun openConnection(
    path: String?,
    inMemory: Boolean,
    readOnly: Boolean,
    diagnostic: (String) -> Unit,
): SqliteConnection {
    val normalized = path?.replace('\\', '/')
    val split = normalized?.lastIndexOf('/') ?: -1
    val logger = object : Logger {
        override val vActive: Boolean = false
        override val eActive: Boolean = true
        override fun trace(message: String) = emit(message, null)
        override fun vWrite(message: String) = emit(message, null)
        override fun eWrite(message: String, exception: Throwable?) = emit(message, exception)
        private fun emit(message: String, exception: Throwable?) {
            val detail = exception?.message?.takeIf { it.isNotBlank() }
            runCatching { diagnostic(if (detail == null) message else "$message: $detail") }
        }
    }
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
        loggingConfig = DatabaseConfiguration.Logging(logger = logger, verboseDataCalls = false),
    )
    val connection = try {
        createDatabaseManager(configuration).createSingleThreadedConnection()
    } catch (cause: Throwable) {
        throw sqliteFailure("open", SQLITE_CANTOPEN, cause = cause)
    }
    val result = NativeConnection(connection)
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

private class CallbackState {
    var authorizer: ((Int, String?, String?, String?, String?) -> Boolean)? = null
    var progress: (() -> Boolean)? = null
}

private fun authorize(
    context: COpaquePointer?, action: Int, first: CPointer<ByteVar>?, second: CPointer<ByteVar>?,
    database: CPointer<ByteVar>?, source: CPointer<ByteVar>?,
): Int = try {
    val callback = context!!.asStableRef<CallbackState>().get().authorizer
    if (callback == null || callback(action, first?.toKString(), second?.toKString(), database?.toKString(), source?.toKString())) SQLITE_OK else SQLITE_DENY
} catch (_: Throwable) { SQLITE_DENY }

private fun progress(context: COpaquePointer?): Int = try {
    if (context!!.asStableRef<CallbackState>().get().progress?.invoke() == true) 1 else 0
} catch (_: Throwable) { 1 }

private class NativeConnection(private val delegate: DatabaseConnection) : SqliteConnection {
    private val pointer = delegate.getDbPointer()
    private val state = CallbackState()
    private val reference = StableRef.create(state)
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

    override fun setAuthorizer(callback: ((Int, String?, String?, String?, String?) -> Boolean)?) {
        check(!closed)
        val code = sqlite3_set_authorizer(pointer, if (callback == null) null else staticCFunction(::authorize), if (callback == null) null else reference.asCPointer())
        if (code != SQLITE_OK) throw sqliteFailure("authorizer", code, pointer)
        state.authorizer = callback
    }

    override fun setProgressHandler(instructions: Int, shouldAbort: (() -> Boolean)?) {
        check(!closed)
        require(instructions > 0)
        sqlite3_progress_handler(pointer, if (shouldAbort == null) 0 else instructions, if (shouldAbort == null) null else staticCFunction(::progress), if (shouldAbort == null) null else reference.asCPointer())
        state.progress = shouldAbort
    }

    override fun interrupt() { check(!closed); sqlite3_interrupt(pointer) }

    override fun close() {
        if (closed) return
        sqlite3_progress_handler(pointer, 0, null, null)
        sqlite3_set_authorizer(pointer, null, null)
        state.authorizer = null
        state.progress = null
        var primary: Throwable? = null
        statements.toList().forEach { statement ->
            try { statement.close() } catch (failure: Throwable) {
                if (primary == null) primary = failure else primary.addSuppressed(failure)
            }
        }
        reference.dispose()
        closed = true
        try { delegate.close() } catch (cause: Throwable) {
            val failure = sqliteFailure("close", SQLITE_ERROR, cause = cause)
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
