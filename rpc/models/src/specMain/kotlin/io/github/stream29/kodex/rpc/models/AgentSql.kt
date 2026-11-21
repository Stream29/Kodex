package io.github.stream29.kodex.rpc.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Byte budget applies to this JSON encoding of a result body, excluding its final stdout newline. */
@OptIn(ExperimentalSerializationApi::class)
public val AgentSqlResultJson: Json = Json { encodeDefaults = true; explicitNulls = false }

public const val AgentSqlQueryModelVersion: Int = 2
public const val AgentSqlInputBytes: Int = 64 * 1024
public const val AgentSqlCellBytes: Int = 256 * 1024
public const val AgentSqlResultBytes: Int = 1024 * 1024
public const val AgentSqlResultRows: Int = 1000
public const val AgentSqlDeadlineMillis: Long = 1000

@Serializable
public enum class AgentSqlError {
    INVALID_INPUT, DENIED, BUSY, TIMEOUT, VALUE_TOO_LARGE, UNAVAILABLE, QUERY_FAILED,
}

@Serializable
public data class AgentSqlDiagnostic(public val code: AgentSqlError, public val message: String)

/**
 * Values are positional. Integers use decimal strings tagged `integer`, blobs use Base64 tagged
 * `blob`, and nonfinite reals are tagged `real`. Finite reals, text, and null are JSON primitives.
 */
@Serializable
public data class AgentSqlQueryResult(
    @SerialName("elapsed_ms") public val elapsedMillis: Long,
    public val columns: List<String>? = null,
    public val rows: List<List<JsonElement>>? = null,
    public val truncated: Boolean? = null,
    public val error: AgentSqlDiagnostic? = null,
    public val engine: String = "sqlite",
    @SerialName("query_model_version") public val queryModelVersion: Int = AgentSqlQueryModelVersion,
) {
    init {
        require(elapsedMillis >= 0)
        if (error == null) {
            require(columns != null && rows != null && truncated != null)
            require(rows.size <= AgentSqlResultRows && rows.all { it.size == columns.size })
        } else require(columns == null && rows == null && truncated == null)
    }
}

@Serializable
public data class AgentSqlColumn(
    public val name: String,
    public val sqliteType: String,
    public val nullable: Boolean,
)

@Serializable
public data class AgentSqlRelation(
    public val name: String,
    public val derived: Boolean,
    public val columns: List<AgentSqlColumn>,
    public val primaryKey: List<String>,
    public val indices: List<String>,
)

@Serializable
public data class AgentSqlExample(public val title: String, public val sql: String)

@Serializable
public data class AgentSqlSchemaResult(
    public val relations: List<AgentSqlRelation>,
    public val functions: List<String>,
    @SerialName("payload_paths") public val payloadPaths: Map<String, List<String>>,
    public val examples: List<AgentSqlExample>,
    public val engine: String = "sqlite",
    @SerialName("query_model_version") public val queryModelVersion: Int = AgentSqlQueryModelVersion,
)
