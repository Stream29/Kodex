package io.github.stream29.kodex.app.migration.v0_4_5

import io.github.stream29.kodex.agentstorage.filesystemlayout.readRecord
import io.github.stream29.kodex.agentstorage.filesystemlayout.recordPath
import io.github.stream29.kodex.agentstorage.filesystemlayout.storedRecordIndexes
import io.github.stream29.kodex.agentstorage.filesystemlayout.timelineDirectory
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal suspend fun migrateToV0_4_5(
    home: Path,
    fileSystem: CoroutineFileSystem,
) {
    val sessions = Path(home, SessionsDirectory)
    val metadata = fileSystem.metadataOrNull(sessions) ?: return
    if (!metadata.isDirectory) {
        throw IOException("Sessions path is not a directory: $sessions")
    }
    fileSystem.list(sessions)
        .filterNot { entry -> entry.name.startsWith('.') }
        .sortedBy { entry -> entry.name.toIntOrNull() ?: error("Invalid Session entry: $entry") }
        .forEach { session ->
            val tokenCount = timelineDirectory(session, TokenCountTimeline)
            storedRecordIndexes(tokenCount, fileSystem).forEach { index ->
                migrateRecord(tokenCount, index, fileSystem)
            }
        }
}

private suspend fun migrateRecord(
    timeline: Path,
    index: Int,
    fileSystem: CoroutineFileSystem,
) {
    val path = recordPath(timeline, index)
    val element = try {
        Json.parseToJsonElement(readRecord(timeline, index, fileSystem).decodeToString())
    } catch (failure: Throwable) {
        throw IOException("Invalid token-count record: $path", failure)
    }
    val objectValue = element as? JsonObject
    if (objectValue != null) {
        val kind = objectValue["kind"]?.jsonPrimitive?.content
        val total = objectValue["total_tokens"]?.jsonPrimitive?.longOrNull
        if (kind in TargetKinds && total != null) return
        throw IOException("Ambiguous token-count record: $path")
    }
    val total = (element as? JsonPrimitive)?.longOrNull
        ?: throw IOException("Invalid token-count value: $path")
    val migrated = buildJsonObject {
        put("kind", "legacy")
        put("total_tokens", total)
    }
    fileSystem.writeString(path, Json.encodeToString(JsonObject.serializer(), migrated))
}

private val TargetKinds = setOf("legacy", "initialization", "compaction", "response")
private const val SessionsDirectory = "sessions"
private const val TokenCountTimeline = "token-count"
