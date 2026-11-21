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

/** One serially owned connection. All operations and close must be serialized. */
public interface SqliteConnection : AutoCloseable {
    /** @throws SqliteFailure when preparation fails. */
    public fun prepare(sql: String): SqliteStatement
    /** Executes one prepared statement; parameters are null, Long, Double, String or ByteArray. */
    public fun execute(sql: String, parameters: List<Any?> = emptyList())
    /** Returns materialized, zero-based columns of null, Long, Double, String or ByteArray. */
    public fun query(sql: String, parameters: List<Any?> = emptyList()): List<List<Any?>>
    /** Returns the number of rows modified, inserted, or deleted by the most recent DML statement. */
    public fun changes(): Int
    /** Uses BEGIN IMMEDIATE; nesting is rejected. No suspension or external work belongs in this scope. */
    public fun <T> transaction(block: () -> T): T
    /** Finalizes outstanding statements, then closes the handle. */
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
 * Opens a SQLiter-managed file connection with a 1000ms busy timeout.
 * Writable connections use WAL/FULL/FK; read connections use query_only/FK after trusted bootstrap.
 * Relative paths use the working directory.
 * @throws SqliteFailure when bootstrap fails; underlying SQLiter exceptions are attached as [SqliteFailure.cause].
 */
public expect fun openSqliteConnection(
    path: Path,
    readOnly: Boolean = false,
): SqliteConnection

/**
 * Opens an existing SQLite file without creating it or selecting a journal mode.
 * Uses normal SQLite locking and recovery, enables foreign keys, and waits up to 1000 ms on locks.
 * Reads may touch WAL sidecars; this is not a filesystem-pure preflight.
 * @throws SqliteFailure if the file cannot be opened.
 */
public expect fun openExistingSqliteConnection(path: Path): SqliteConnection

/**
 * Opens an isolated in-memory SQLite connection with foreign keys enabled.
 * @throws SqliteFailure when bootstrap fails; underlying SQLiter exceptions are attached as [SqliteFailure.cause].
 */
public expect fun openInMemorySqliteConnection(): SqliteConnection
