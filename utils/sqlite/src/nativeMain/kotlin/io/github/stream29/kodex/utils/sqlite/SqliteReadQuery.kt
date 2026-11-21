@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.utils.sqlite

import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import io.github.stream29.kodex.utils.sqlite.bundled.*
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.time.TimeMark

private class QueryScope(val policy: SqliteReadPolicy, private val deadline: TimeMark, private val job: Job) {
    val timedOut: Boolean get() = deadline.hasPassedNow()
    val cancelledOrExpired: Boolean get() = !job.isActive || timedOut

    fun checkBudget() {
        job.ensureActive()
        if (timedOut) throw SqliteReadFailure(SqliteReadFailureKind.Timeout)
    }

    fun check(code: Int, kind: SqliteReadFailureKind = SqliteReadFailureKind.Failed) {
        checkBudget()
        if (code != SQLITE_OK) throw SqliteReadFailure(when (code and 255) {
            SQLITE_AUTH -> SqliteReadFailureKind.Denied
            SQLITE_TOOBIG -> SqliteReadFailureKind.ValueTooLarge
            else -> kind
        })
    }
}

private val queryProgress = staticCFunction { raw: COpaquePointer? ->
    if (raw!!.asStableRef<QueryScope>().get().cancelledOrExpired) 1 else 0
}

private val queryBusy = staticCFunction { raw: COpaquePointer?, _: Int ->
    val scope = raw!!.asStableRef<QueryScope>().get()
    if (!scope.cancelledOrExpired) sqlite3_sleep(1)
    if (scope.cancelledOrExpired) 0 else 1
}

private val queryAuthorizer = staticCFunction {
        raw: COpaquePointer?, code: Int, first: CPointer<ByteVar>?, second: CPointer<ByteVar>?,
        database: CPointer<ByteVar>?, _: CPointer<ByteVar>? ->
    val policy = raw!!.asStableRef<QueryScope>().get().policy
    val allowed = when (code) {
        SQLITE_SELECT, SQLITE_RECURSIVE -> true
        SQLITE_READ -> {
            val from = database?.toKString()
            (from == "main" || (from == null && second?.get(0) == 0.toByte())) &&
                first?.toKString()?.lowercase() in policy.relations
        }
        SQLITE_FUNCTION -> second?.toKString()?.lowercase() in policy.functions
        else -> false
    }
    if (allowed) SQLITE_OK else SQLITE_DENY
}

public actual suspend fun <R> withSqliteReadQuery(
    path: Path, sql: String, policy: SqliteReadPolicy, deadline: TimeMark, read: (SqliteReadRows) -> R,
): R = withContext(Dispatchers.IO) {
    val scope = QueryScope(policy, deadline, currentCoroutineContext().job)
    scope.checkBudget()
    if (sql.length > policy.inputBytes || sql.isBlank() || '\u0000' in sql ||
        sql.encodeToByteArray().size > policy.inputBytes
    ) {
        throw SqliteReadFailure(SqliteReadFailureKind.InvalidStatement)
    }
    val hooks = StableRef.create(scope)
    try {
        memScoped {
            val pointer = alloc<CPointerVar<sqlite3>>()
            pointer.value = null
            val code = sqlite3_open_v2(path.toString(), pointer.ptr, SQLITE_OPEN_READONLY, null)
            val db = pointer.value
            try {
                scope.check(code, SqliteReadFailureKind.Unavailable)
                checkNotNull(db)
                sqlite3_progress_handler(db, 100, queryProgress, hooks.asCPointer())
                scope.check(sqlite3_busy_handler(db, queryBusy, hooks.asCPointer()))
                requireUserVersion(db, scope)
                sqlite3_limit(db, SQLITE_LIMIT_SQL_LENGTH, policy.inputBytes)
                scope.check(sqlite3_set_authorizer(db, queryAuthorizer, hooks.asCPointer()))
                val statement = alloc<CPointerVar<sqlite3_stmt>>()
                statement.value = null
                val tail = alloc<CPointerVar<ByteVar>>()
                fun prepare(input: CPointer<ByteVar>, dest: CPointerVar<sqlite3_stmt>) {
                    scope.checkBudget()
                    scope.check(
                        sqlite3_prepare_v3(db, input, -1, SQLITE_PREPARE_NO_VTAB.convert(), dest.ptr, tail.ptr),
                        SqliteReadFailureKind.InvalidStatement,
                    )
                }
                try {
                    prepare(sql.cstr.getPointer(this), statement)
                    val head = statement.value ?: throw SqliteReadFailure(SqliteReadFailureKind.InvalidStatement)
                    if (sqlite3_stmt_readonly(head) == 0 || sqlite3_stmt_isexplain(head) != 0) {
                        throw SqliteReadFailure(SqliteReadFailureKind.Denied)
                    }
                    val next = alloc<CPointerVar<sqlite3_stmt>>()
                    while (true) {
                        val rest = tail.value ?: break
                        if (rest[0] == 0.toByte()) break
                        next.value = null
                        try {
                            prepare(rest, next)
                            if (next.value != null) throw SqliteReadFailure(SqliteReadFailureKind.InvalidStatement)
                            if (rest == tail.value) throw SqliteReadFailure(SqliteReadFailureKind.InvalidStatement)
                        } finally {
                            next.value?.let { sqlite3_finalize(it) }
                        }
                    }
                    read(NativeReadRows(head, scope)).also { scope.checkBudget() }
                } finally {
                    statement.value?.let { sqlite3_finalize(it) }
                }
            } finally {
                if (db != null) {
                    sqlite3_progress_handler(db, 0, null, null)
                    sqlite3_busy_handler(db, null, null)
                    sqlite3_set_authorizer(db, null, null)
                    sqlite3_close_v2(db)
                }
            }
        }
    } finally {
        hooks.dispose()
    }
}

private fun requireUserVersion(db: CPointer<sqlite3>, scope: QueryScope) = memScoped {
    val statement = alloc<CPointerVar<sqlite3_stmt>>()
    statement.value = null
    try {
        scope.check(sqlite3_prepare_v2(db, "PRAGMA main.user_version".cstr.getPointer(this), -1, statement.ptr, null))
        val version = checkNotNull(statement.value)
        val stepped = sqlite3_step(version)
        scope.checkBudget()
        if (stepped != SQLITE_ROW) scope.check(stepped)
        if (stepped != SQLITE_ROW || sqlite3_column_int(version, 0) != scope.policy.requiredUserVersion) {
            throw SqliteReadFailure(SqliteReadFailureKind.IncompatibleSchema)
        }
    } finally {
        statement.value?.let { sqlite3_finalize(it) }
    }
}

private class NativeReadRows(private val statement: CPointer<sqlite3_stmt>, private val scope: QueryScope) : SqliteReadRows {
    override val columns: List<String> get() = List(sqlite3_column_count(statement)) { index ->
        scope.checkBudget()
        sqlite3_column_name(statement, index)?.toKString() ?: throw SqliteReadFailure(SqliteReadFailureKind.Failed)
    }

    override fun checkBudget() = scope.checkBudget()

    override fun next(): Boolean {
        scope.checkBudget()
        return when (val code = sqlite3_step(statement)) {
            SQLITE_ROW -> true
            SQLITE_DONE -> false
            else -> {
                scope.check(code)
                error("Unreachable")
            }
        }
    }

    override fun cell(index: Int): Any? {
        scope.checkBudget()
        return when (sqlite3_column_type(statement, index)) {
            SQLITE_INTEGER -> sqlite3_column_int64(statement, index)
            SQLITE_FLOAT -> sqlite3_column_double(statement, index)
            SQLITE_NULL -> null
            SQLITE_TEXT -> {
                val text = sqlite3_column_text(statement, index)?.reinterpret<ByteVar>()
                bytes(text, index).decodeToString(throwOnInvalidSequence = true)
            }
            SQLITE_BLOB -> bytes(sqlite3_column_blob(statement, index)?.reinterpret<ByteVar>(), index)
            else -> throw SqliteReadFailure(SqliteReadFailureKind.Failed)
        }
    }

    private fun bytes(pointer: CPointer<ByteVar>?, index: Int): ByteArray {
        val size = sqlite3_column_bytes(statement, index)
        if (size > scope.policy.cellBytes) throw SqliteReadFailure(SqliteReadFailureKind.ValueTooLarge)
        if (size == 0) return byteArrayOf()
        return pointer?.readBytes(size) ?: throw SqliteReadFailure(SqliteReadFailureKind.Failed)
    }
}
