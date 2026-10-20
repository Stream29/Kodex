package io.github.stream29.kodex.app.migration.v0_5_2

import io.github.stream29.kodex.agentstorage.filesystemlayout.readRecord
import io.github.stream29.kodex.agentstorage.filesystemlayout.recordPath
import io.github.stream29.kodex.agentstorage.filesystemlayout.storedRecordIndexes
import io.github.stream29.kodex.agentstorage.filesystemlayout.timelineDirectory
import io.github.stream29.kodex.agentstorage.filesystemlayout.writeRecord
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Frozen 0.5.2 Home migration that populates missing `yield_time_ms` in historical
 * typed command execution events across root Session `work` and `unstable` timelines.
 *
 * Uses frozen JSON literals and filesystem-layout primitives without referencing
 * mutable current DTOs or runtime storage codecs.
 */
internal suspend fun migrateToV0_5_2(
    home: Path,
    fileSystem: CoroutineFileSystem,
) {
    currentCoroutineContext().ensureActive()
    val sessions = Path(home, SessionsDirectory)
    val metadata = fileSystem.metadataOrNull(sessions) ?: return
    if (!metadata.isDirectory) {
        throw IOException("Sessions path is not a directory: $sessions")
    }
    fileSystem.list(sessions)
        .filterNot { entry -> entry.name.startsWith('.') }
        .sortedBy { entry -> entry.name.toCanonicalIndex(sessions) }
        .forEach { session ->
            currentCoroutineContext().ensureActive()
            if (fileSystem.metadataOrNull(session)?.isDirectory != true) {
                throw IOException("Session entry is not a directory: $session")
            }
            migrateWorkTimeline(timelineDirectory(session, WorkTimeline), fileSystem)
            migrateUnstableTimeline(timelineDirectory(session, UnstableTimeline), fileSystem)
        }
}

private suspend fun migrateWorkTimeline(
    work: Path,
    fileSystem: CoroutineFileSystem,
) {
    val metadata = fileSystem.metadataOrNull(work) ?: return
    if (!metadata.isDirectory) {
        throw IOException("Work timeline is not a directory: $work")
    }
    storedRecordIndexes(work, fileSystem).forEach { index ->
        currentCoroutineContext().ensureActive()
        val path = recordPath(work, index)
        val element = parseJsonRecord(readRecord(work, index, fileSystem), path)
        val event = element as? JsonObject
            ?: throw IOException("Work record is not a JSON object: $path")
        val migrated = migrateWorkEvent(event, path) ?: return@forEach
        writeRecord(
            timelineDirectory = work,
            index = index,
            content = MigrationJson.encodeToString(JsonObject.serializer(), migrated).encodeToByteArray(),
            fileSystem = fileSystem,
        )
    }
}

private suspend fun migrateUnstableTimeline(
    unstable: Path,
    fileSystem: CoroutineFileSystem,
) {
    val metadata = fileSystem.metadataOrNull(unstable) ?: return
    if (!metadata.isDirectory) {
        throw IOException("Unstable timeline is not a directory: $unstable")
    }
    storedRecordIndexes(unstable, fileSystem).forEach { index ->
        currentCoroutineContext().ensureActive()
        val path = recordPath(unstable, index)
        val element = parseJsonRecord(readRecord(unstable, index, fileSystem), path)
        val events = element as? JsonArray
            ?: throw IOException("Unstable record is not a JSON array: $path")
        var changed = false
        val migratedEvents = JsonArray(
            events.map { item ->
                val event = item as? JsonObject
                    ?: throw IOException("Unstable event is not a JSON object: $path")
                val migrated = migrateUnstableEvent(event, path)
                if (migrated != null) {
                    changed = true
                    migrated
                } else {
                    event
                }
            },
        )
        if (!changed) return@forEach
        writeRecord(
            timelineDirectory = unstable,
            index = index,
            content = MigrationJson.encodeToString(JsonArray.serializer(), migratedEvents).encodeToByteArray(),
            fileSystem = fileSystem,
        )
    }
}

private fun migrateWorkEvent(
    event: JsonObject,
    path: Path,
): JsonObject? {
    val type = (event[TypeField] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IOException("Work record is missing string discriminator '$TypeField': $path")
    if (type != WorkCommandEventType) return null
    if (CallIdField in event && (event[CallIdField] as? JsonPrimitive)?.isString != true) {
        throw IOException("Command execution work event has invalid '$CallIdField': $path")
    }
    if (ResultField in event && event[ResultField] !is JsonObject) {
        throw IOException("Command execution work event has invalid '$ResultField': $path")
    }
    val action = event[ActionField] as? JsonObject
        ?: throw IOException("Command execution work event has invalid '$ActionField': $path")
    val migratedAction = migrateCommandAction(action, path) ?: return null
    return replaceField(event, ActionField, migratedAction)
}

private fun migrateUnstableEvent(
    event: JsonObject,
    path: Path,
): JsonObject? {
    val type = (event[TypeField] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IOException("Unstable event is missing string discriminator '$TypeField': $path")
    if (type != UnstableCommandEventType) return null
    if (CallIdField in event && (event[CallIdField] as? JsonPrimitive)?.isString != true) {
        throw IOException("Pending command execution event has invalid '$CallIdField': $path")
    }
    val action = event[ActionField] as? JsonObject
        ?: throw IOException("Pending command execution event has invalid '$ActionField': $path")
    val migratedAction = migrateCommandAction(action, path) ?: return null
    return replaceField(event, ActionField, migratedAction)
}

private fun migrateCommandAction(
    action: JsonObject,
    path: Path,
): JsonObject? {
    val actionType = (action[TypeField] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IOException("Command action is missing string discriminator '$TypeField': $path")
    val arguments = action[ArgumentsField] as? JsonObject
        ?: throw IOException("Command action '$actionType' has invalid '$ArgumentsField': $path")
    val defaultYieldTimeMillis = when (actionType) {
        ExecCommandActionType -> {
            (arguments[CommandField] as? JsonPrimitive)?.takeIf { it.isString }
                ?: throw IOException("Invalid '$CommandField' in '$ExecCommandActionType' arguments: $path")
            DefaultExecYieldTimeMillis
        }

        WriteStdinActionType -> {
            (arguments[SessionIdField] as? JsonPrimitive)
                ?.takeUnless { it is JsonNull || it.isString }
                ?.intOrNull
                ?: throw IOException("Invalid '$SessionIdField' in '$WriteStdinActionType' arguments: $path")
            if (CharsField in arguments) {
                (arguments[CharsField] as? JsonPrimitive)?.takeIf { it.isString }
                    ?: throw IOException("Invalid '$CharsField' in '$WriteStdinActionType' arguments: $path")
            }
            DefaultWriteStdinYieldTimeMillis
        }

        else -> throw IOException("Unknown command action type '$actionType': $path")
    }
    if (YieldTimeMillisField in arguments) {
        val yieldElement = arguments.getValue(YieldTimeMillisField)
        (yieldElement as? JsonPrimitive)
            ?.takeUnless { it is JsonNull || it.isString }
            ?.longOrNull
            ?: throw IOException("Invalid '$YieldTimeMillisField' in '$actionType' arguments: $path")
        return null
    }
    val migratedArguments = insertYieldTimeMillis(arguments, defaultYieldTimeMillis)
    return replaceField(action, ArgumentsField, migratedArguments)
}

private fun insertYieldTimeMillis(
    arguments: JsonObject,
    defaultYieldTimeMillis: Long,
): JsonObject = buildJsonObject {
    var inserted = false
    for ((key, value) in arguments) {
        if (key == MaxOutputTokensField && !inserted) {
            put(YieldTimeMillisField, JsonPrimitive(defaultYieldTimeMillis))
            inserted = true
        }
        put(key, value)
    }
    if (!inserted) {
        put(YieldTimeMillisField, JsonPrimitive(defaultYieldTimeMillis))
    }
}

private fun replaceField(
    source: JsonObject,
    fieldName: String,
    replacement: JsonElement,
): JsonObject = buildJsonObject {
    for ((key, value) in source) {
        put(key, if (key == fieldName) replacement else value)
    }
}

private fun parseJsonRecord(
    encoded: ByteArray,
    path: Path,
): JsonElement =
    try {
        MigrationJson.parseToJsonElement(encoded.decodeToString())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        throw IOException("Corrupted JSON record at $path", failure)
    }

private fun String.toCanonicalIndex(parent: Path): Int {
    val index = toIntOrNull()?.takeIf { it >= 0 }
        ?: throw IOException("Invalid Session entry: ${Path(parent, this)}")
    if (index.toString() != this) {
        throw IOException("Invalid Session entry: ${Path(parent, this)}")
    }
    return index
}

private val MigrationJson: Json = Json

private const val SessionsDirectory: String = "sessions"
private const val WorkTimeline: String = "work"
private const val UnstableTimeline: String = "unstable"

private const val TypeField: String = "type"
private const val CallIdField: String = "call_id"
private const val ActionField: String = "action"
private const val ResultField: String = "result"
private const val ArgumentsField: String = "arguments"

private const val WorkCommandEventType: String = "command_execution_tool_event"
private const val UnstableCommandEventType: String = "command_execution"
private const val ExecCommandActionType: String = "exec_command"
private const val WriteStdinActionType: String = "write_stdin"

private const val CommandField: String = "cmd"
private const val SessionIdField: String = "session_id"
private const val CharsField: String = "chars"
private const val YieldTimeMillisField: String = "yield_time_ms"
private const val MaxOutputTokensField: String = "max_output_tokens"

private const val DefaultExecYieldTimeMillis: Long = 10_000L
private const val DefaultWriteStdinYieldTimeMillis: Long = 250L
