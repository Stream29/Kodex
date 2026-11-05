package io.github.stream29.kodex.utils.sqlite

import kotlinx.io.files.Path

/** A SQLite failure preserving operation name, numeric result code, engine message, and cause. */
public class SqliteFailure(
    public val operation: String,
    public val code: Int,
    public val sqliteMessage: String? = null,
    cause: Throwable? = null,
) : RuntimeException(
    if (sqliteMessage.isNullOrBlank()) {
        "SQLite $operation failed (code=$code)"
    } else {
        "SQLite $operation failed (code=$code): $sqliteMessage"
    },
    cause,
)

/**
 * One serially owned connection. All operations, callback replacement and close must be serialized.
 * Only [interrupt] may run concurrently, while the owner guarantees this connection remains open.
 * Callbacks may only reenter SQLite through [interrupt]; callback exceptions deny/abort the operation.
 */
public interface SqliteConnection : AutoCloseable {
    /** @throws SqliteFailure when preparation fails. */
    public fun prepare(sql: String): SqliteStatement
    /** Executes one prepared statement; parameters are null, Long, Double, String or ByteArray. */
    public fun execute(sql: String, parameters: List<Any?> = emptyList())
    /** Returns materialized, zero-based columns of null, Long, Double, String or ByteArray. */
    public fun query(sql: String, parameters: List<Any?> = emptyList()): List<List<Any?>>
    /** Uses BEGIN IMMEDIATE; nesting is rejected. No suspension or external work belongs in this scope. */
    public fun <T> transaction(block: () -> T): T
    /** Receives SQLite action, first/second argument, database and trigger/view name; false denies. */
    public fun setAuthorizer(callback: ((Int, String?, String?, String?, String?) -> Boolean)?)
    /** Installs an abort predicate called every [instructions] VM instructions; null removes it. */
    public fun setProgressHandler(instructions: Int = 1000, shouldAbort: (() -> Boolean)?)
    /** Interrupts only this connection's currently active operation. */
    public fun interrupt()
    /** Finalizes outstanding statements, removes callbacks, releases references, then closes the handle. */
    override fun close()
}

/** A statement belongs to its connection; close is idempotent and preserves the primary step failure. */
public interface SqliteStatement : AutoCloseable {
    /** One-based index. @throws IllegalArgumentException for unsupported parameter types. */
    public fun bind(index: Int, value: Any?)
    /** @return true for a row, false for completion. @throws SqliteFailure on step failure. */
    public fun step(): Boolean
    /** Zero-based columns for the current row. */
    public fun columns(): List<Any?>
    /** Resets execution and bindings; a repeated result from a failed step cannot replace that failure. */
    public fun reset()
    override fun close()
}

/**
 * Opens a SQLiter-managed file connection with a 1000ms busy timeout and diagnostic sink.
 * Writable connections use WAL/FULL/FK; read connections use query_only/FK after trusted bootstrap.
 * Use a separate connection for cancellable reads. Relative paths use the working directory.
 * @throws SqliteFailure when bootstrap fails; underlying SQLiter exceptions are attached as [SqliteFailure.cause].
 */
public expect fun openSqliteConnection(
    path: Path,
    readOnly: Boolean = false,
    diagnostic: (String) -> Unit = {},
): SqliteConnection

/**
 * Opens an isolated in-memory SQLite connection with foreign keys enabled.
 * @throws SqliteFailure when bootstrap fails; underlying SQLiter exceptions are attached as [SqliteFailure.cause].
 */
public expect fun openInMemorySqliteConnection(
    diagnostic: (String) -> Unit = {},
): SqliteConnection

/** Returns the linked engine's sqlite3_libversion(), independently of the host library. */
public expect fun sqlite3Version(): String
