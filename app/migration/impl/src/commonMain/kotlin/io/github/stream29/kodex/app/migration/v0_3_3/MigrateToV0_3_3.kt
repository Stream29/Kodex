package io.github.stream29.kodex.app.migration.v0_3_3

import io.github.stream29.kodex.agentstorage.filesystemlayout.readRecord
import io.github.stream29.kodex.agentstorage.filesystemlayout.readRecordPrefix
import io.github.stream29.kodex.agentstorage.filesystemlayout.recordPath
import io.github.stream29.kodex.agentstorage.filesystemlayout.storedRecordIndexes
import io.github.stream29.kodex.agentstorage.filesystemlayout.timelineDirectory
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal suspend fun migrateToV0_3_3(
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
        .sortedBy { entry -> entry.name.toCanonicalIndex() }
        .forEach { session ->
            if (fileSystem.metadataOrNull(session)?.isDirectory != true) {
                throw IOException("Session entry is not a directory: $session")
            }
            migrateWorkTimeline(timelineDirectory(session, WorkTimeline), fileSystem)
        }
}

private suspend fun migrateWorkTimeline(
    work: Path,
    fileSystem: CoroutineFileSystem,
) {
    storedRecordIndexes(work, fileSystem).forEach { index ->
        val temporary = migrationTemporaryPath(work, index)
        withContext(NonCancellable) {
            fileSystem.delete(temporary, mustExist = false)
        }
        val prefix = readRecordPrefix(
            timelineDirectory = work,
            index = index,
            fileSystem = fileSystem,
            byteCount = PatchEventPrefix.size.toLong(),
        )
        if (!prefix.contentEquals(PatchEventPrefix)) return@forEach
        val encoded = readRecord(work, index, fileSystem)
        val migrated = removeLegacyPatchDelta(encoded) ?: return@forEach
        try {
            fileSystem.writeBytes(temporary, migrated, mustCreate = true)
            fileSystem.atomicMove(temporary, recordPath(work, index))
        } finally {
            withContext(NonCancellable) {
                fileSystem.delete(temporary, mustExist = false)
            }
        }
    }
}

internal fun removeLegacyPatchDelta(encoded: ByteArray): ByteArray? {
    if (!encoded.startsWith(PatchEventPrefix)) return null
    val event = MigrationJson.parseToJsonElement(encoded.decodeToString()).jsonObject
    if (event["type"]?.jsonPrimitive?.content != PatchEventType) {
        throw IOException("Patch event discriminator changed during migration.")
    }
    val result = event["result"]?.jsonObject
        ?: throw IOException("Patch event result is not an object.")
    return when (result["type"]?.jsonPrimitive?.content) {
        FailureResultType -> null
        SuccessResultType -> {
            val applyResult = result["apply_result"]?.jsonObject
                ?: throw IOException("Successful patch event has no apply_result object.")
            if ("delta" !in applyResult) return null
            val migratedApplyResult = JsonObject(applyResult - "delta")
            val migratedResult = JsonObject(result + ("apply_result" to migratedApplyResult))
            val migratedEvent = JsonObject(event + ("result" to migratedResult))
            MigrationJson.encodeToString(JsonObject.serializer(), migratedEvent).encodeToByteArray()
        }

        else -> throw IOException("Patch event has an unknown result type.")
    }
}

internal fun migrationTemporaryPath(work: Path, index: Int): Path =
    Path(work, ".kodex-migration-0.3.3-patch-$index.tmp")

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    return prefix.indices.all { index -> this[index] == prefix[index] }
}

private fun String.toCanonicalIndex(): Int {
    val index = toIntOrNull()?.takeIf { it >= 0 }
        ?: throw IOException("Invalid Session entry: $this")
    if (index.toString() != this) {
        throw IOException("Invalid Session entry: $this")
    }
    return index
}

private val MigrationJson = Json
private val PatchEventPrefix: ByteArray = "{\"type\":\"patch_tool_event\",".encodeToByteArray()
private const val SessionsDirectory: String = "sessions"
private const val WorkTimeline: String = "work"
private const val PatchEventType: String = "patch_tool_event"
private const val SuccessResultType: String = "success"
private const val FailureResultType: String = "failure"
