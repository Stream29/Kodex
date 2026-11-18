package io.github.stream29.kodex.utils.sqlite

import kotlinx.io.files.Path
import kotlin.time.TimeMark

public enum class SqliteReadFailureKind { Unavailable, Denied, InvalidStatement, Timeout, ValueTooLarge, Failed }

/** Safe classification for the caller, not raw SQLite or file-system diagnostics. */
public class SqliteReadFailure(public val kind: SqliteReadFailureKind) : IllegalStateException(kind.name)

public data class SqliteReadPolicy(
    public val relations: Set<String>,
    public val functions: Set<String>,
    public val inputBytes: Int,
    public val cellBytes: Int,
)

/** One borrowed SQLite statement. Consume it only inside [withSqliteReadQuery]. */
public interface SqliteReadRows {
    public val columns: List<String>
    public fun next(): Boolean
    /** Checks the raw cell byte limit before allocating a Kotlin string or byte array. */
    public fun cell(index: Int): Any?
    public fun checkBudget()
}

/**
 * Owns one SQLITE_OPEN_READONLY connection, with a positive object/function allowlist and exactly
 * one non-EXPLAIN statement. The caller owns admission and output encoding.
 *
 * Callback checks cover SQLite prepare, VM execution and busy waits. Cancellation and deadline
 * are checked during result extraction, but cannot preempt arbitrary kernel I/O. All callbacks
 * and the connection are released before this function returns or throws.
 */
public expect suspend fun <R> withSqliteReadQuery(
    path: Path, sql: String, policy: SqliteReadPolicy, deadline: TimeMark, read: (SqliteReadRows) -> R,
): R
