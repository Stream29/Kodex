package io.github.stream29.kodex.app.migration.v0_8_0

import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

private const val ResponseType = "llm_request_meta_response"
private const val NanosecondsPerSecond = 1_000_000_000L
private val UnrecordedTime = Instant.fromEpochSeconds(0)

/**
 * Estimates old response duration from persisted commit times, not stream observations.
 * The staged database owner runs this synchronously inside its write transaction.
 */
internal fun backfillRecentHomeResponseTps(connection: SqliteConnection) {
    var after: List<Any?> = listOf(-1L, -1L)
    while (true) {
        val rows = connection.query(
            """
            SELECT agent_id, "index", payload_json, created_at FROM agent_commits
            WHERE type = '$ResponseType' AND (agent_id, "index") > (?, ?)
            ORDER BY agent_id, "index" LIMIT 32
            """.trimIndent(),
            after,
        )
        if (rows.isEmpty()) return
        for ((rawAgent, rawIndex, rawPayload, rawTimestamp) in rows) {
            val agent = rawAgent as Long
            val index = rawIndex as Long
            val original = rawPayload as String
            val replacement = try {
                connection.backfillResponse(agent, index, original, rawTimestamp as String)
            } catch (failure: InvalidRecentResponse) {
                // Keep the response locator and a fixed reason, never JSON or timestamp text.
                error("Cannot backfill recent response TPS (agent_id=$agent, index=$index): ${failure.message}")
            } ?: continue
            connection.execute(
                """UPDATE agent_commits SET payload_json = ?
                   WHERE agent_id = ? AND type = '$ResponseType' AND "index" = ? AND payload_json = ?""",
                listOf(replacement, agent, index, original),
            )
            check(connection.changes() == 1) { "Recent response row changed during TPS backfill (agent_id=$agent, index=$index)." }
        }
        after = rows.last().take(2)
    }
}

private fun SqliteConnection.backfillResponse(
    agent: Long,
    index: Long,
    original: String,
    timestamp: String,
): String? {
    val response = try {
        Json.parseToJsonElement(original) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } ?: invalidResponse("expected a JSON response object")
    val tag = response["type"] as? JsonPrimitive
    if (tag?.isString != true || tag.content != ResponseType) invalidResponse("response discriminator does not match its row type")
    if (query(
        """
        SELECT 1 FROM json_tree(?) WHERE typeof(key) = 'text'
        GROUP BY parent, key HAVING count(*) > 1 LIMIT 1
        """.trimIndent(),
        listOf(original),
    ).isNotEmpty()) invalidResponse("duplicate JSON member")

    val usage = response["usage"] as? JsonObject
        ?: invalidResponse("missing or invalid usage object")
    val outputTokens = usage["output_tokens"].longOrNull()?.takeIf { it >= 0 }
        ?: invalidResponse("usage.output_tokens must be a nonnegative JSON integer")
    val hasElapsed = "elapsed_ns" in response
    val hasOutput = "output_tokens" in response
    if (hasElapsed != hasOutput) invalidResponse("partial TPS fields; cannot mix stored and estimated values")

    val completedAt = parseResponseTime(timestamp, "invalid response timestamp")
    if (completedAt == UnrecordedTime) invalidResponse("response timestamp is the legacy epoch sentinel")
    val batchTimes = query(
        """SELECT created_at FROM agent_commits WHERE agent_id = ? AND "index" = ?""",
        listOf(agent, index),
    )
    if (batchTimes.isEmpty() || batchTimes.any {
        parseResponseTime(it.single() as String, "invalid timestamp in response index") != completedAt
    }) invalidResponse("conflicting timestamps at response index")
    val elapsed = estimateResponseElapsed(agent, index, completedAt)

    if (hasElapsed) {
        val recordedElapsed = response["elapsed_ns"].longOrNull()?.takeIf { it > 0 }
            ?: invalidResponse("elapsed_ns must be a positive JSON integer")
        val recordedOutput = response["output_tokens"].longOrNull()?.takeIf { it >= 0 }
            ?: invalidResponse("output_tokens must be a nonnegative JSON integer")
        if (recordedElapsed != elapsed) invalidResponse("elapsed_ns does not match the same-Agent predecessor estimate")
        if (recordedOutput != outputTokens) invalidResponse("output_tokens does not match usage.output_tokens")
        return null
    }
    return JsonObject(response + mapOf(
        "elapsed_ns" to JsonPrimitive(elapsed),
        "output_tokens" to JsonPrimitive(outputTokens),
    )).toString()
}

private fun SqliteConnection.estimateResponseElapsed(agent: Long, index: Long, completedAt: Instant): Long {
    var before = index
    while (true) {
        // A commit batch has one row per kind. Read all rows of the nearest lower
        // index so no ordering of kinds can hide inconsistent timestamps.
        val rows = query(
            """
            SELECT "index", created_at FROM agent_commits
            WHERE agent_id = ? AND "index" = (
                SELECT max("index") FROM agent_commits WHERE agent_id = ? AND "index" < ?
            )
            """.trimIndent(),
            listOf(agent, agent, before),
        )
        if (rows.isEmpty()) invalidResponse("no earlier same-Agent commit with a usable timestamp")
        val predecessorIndex = rows.first()[0] as Long
        val badTimestamp = "invalid timestamp at predecessor index=$predecessorIndex"
        val predecessor = parseResponseTime(rows.first()[1] as String, badTimestamp)
        if (rows.any { parseResponseTime(it[1] as String, badTimestamp) != predecessor }) {
            invalidResponse("conflicting timestamps at predecessor index=$predecessorIndex")
        }
        if (predecessor == UnrecordedTime || predecessor == completedAt) {
            before = predecessorIndex
            continue
        }
        if (predecessor > completedAt) invalidResponse("timestamp at predecessor index=$predecessorIndex is later than the response")
        return exactElapsedNanoseconds(predecessor, completedAt)
    }
}

private fun exactElapsedNanoseconds(start: Instant, end: Instant): Long {
    // Instant's epochSeconds range fits this subtraction. Avoid Duration's precision
    // changes for large intervals and check overflow before multiplying.
    var seconds = end.epochSeconds - start.epochSeconds
    var nanos = end.nanosecondsOfSecond.toLong() - start.nanosecondsOfSecond
    if (nanos < 0) {
        seconds--
        nanos += NanosecondsPerSecond
    }
    if (seconds < 0) invalidResponse("predecessor elapsed nanoseconds must be strictly positive")
    if (seconds > (Long.MAX_VALUE - nanos) / NanosecondsPerSecond) invalidResponse("predecessor elapsed nanoseconds overflow Long")
    return (seconds * NanosecondsPerSecond + nanos).takeIf { it > 0 }
        ?: invalidResponse("predecessor elapsed nanoseconds must be strictly positive")
}

private fun parseResponseTime(raw: String, reason: String): Instant =
    try {
        Instant.parse(raw)
    } catch (_: IllegalArgumentException) {
        invalidResponse(reason)
    }

private fun JsonElement?.longOrNull(): Long? =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()

private class InvalidRecentResponse(message: String) : IllegalArgumentException(message)

private fun invalidResponse(reason: String): Nothing = throw InvalidRecentResponse(reason)
