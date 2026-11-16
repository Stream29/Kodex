package io.github.stream29.kodex.app.migration.v0_7_2

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlPath
import com.charleskorn.kaml.YamlScalar
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserAnswer
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAskUserEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableJsonToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMediaResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMediaToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.decodePayloadJson
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.encodePayloadJson
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingReadMediaToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.app.migration.v0_4_7.SettingsCodec
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.tool.readmedia.MediaDetail
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolArguments
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolOutput
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

internal const val InterruptedByV0_7_2Message: String = "Tool call interrupted by v0.7.2 migration."

internal suspend fun migrateSettingsHooksToV0_7_2(home: Path, fileSystem: CoroutineFileSystem) {
    val target = Path(home, "settings.yml")
    if (!fileSystem.exists(target)) return
    val root = try {
        SettingsCodec.parse(fileSystem.readString(target))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw IOException("Invalid settings.yml during 0.7.2 migration: $target", failure)
    }

    var changed = false
    fun rewriteHooksSection(section: YamlMap): YamlMap {
        val hooksNode = section.get<YamlNode>("hooks") ?: return section
        val hooksList = hooksNode as? YamlList
            ?: throw IOException("Expected YAML list for hooks in $target")
        var sectionChanged = false
        val updatedHooks = hooksList.items.mapNotNull { item ->
            val hookMap = item as? YamlMap
                ?: throw IOException("Expected YAML mapping for hook entry in $target")
            val typesNode = hookMap.get<YamlNode>("types") as? YamlList
                ?: return@mapNotNull hookMap
            val originalNames = typesNode.items.map { scalarNode ->
                (scalarNode as? YamlScalar)?.content
                    ?: throw IOException("Expected scalar hook type in $target")
            }
            val rewrittenNames = buildList {
                for (name in originalNames) {
                    when (name) {
                        "stop_request_user_input" -> add("ask_user_created")
                        "stop_suggest_subagent" -> Unit
                        else -> add(name)
                    }
                }
            }.distinct()
            if (rewrittenNames != originalNames) {
                sectionChanged = true
            }
            if (rewrittenNames.isEmpty()) {
                sectionChanged = true
                null
            } else if (rewrittenNames == originalNames) {
                hookMap
            } else {
                replaceYamlKey(
                    hookMap,
                    "types",
                    YamlList(rewrittenNames.map { YamlScalar(it, YamlPath.root) }, YamlPath.root),
                )
            }
        }
        if (!sectionChanged) return section
        changed = true
        return replaceYamlKey(section, "hooks", YamlList(updatedHooks, YamlPath.root))
    }

    var updatedRoot = root
    for (sectionName in listOf("backend", "frontend")) {
        val section = updatedRoot.get<YamlMap>(sectionName) ?: continue
        val rewritten = rewriteHooksSection(section)
        if (rewritten !== section) {
            updatedRoot = replaceYamlKey(updatedRoot, sectionName, rewritten)
        }
    }
    if (!changed) return

    val temporary = Path(home, ".settings.migration-0.7.2.tmp")
    try {
        fileSystem.delete(temporary, mustExist = false)
        fileSystem.writePrivateString(temporary, SettingsCodec.encode(updatedRoot), mustCreate = true)
        fileSystem.atomicMove(temporary, target)
    } finally {
        withContext(NonCancellable) {
            fileSystem.delete(temporary, mustExist = false)
        }
    }
}

private fun replaceYamlKey(map: YamlMap, key: String, value: YamlNode): YamlMap =
    YamlMap(
        map.entries.filterKeys { it.content != key } + (YamlScalar(key, YamlPath.root) to value),
        YamlPath.root,
    )

internal fun mayContainRetiredUnstableItem(payloadJson: String): Boolean =
    payloadJson.length > 2 && (
        "image_view" in payloadJson ||
            "request_user_input" in payloadJson ||
            "suggest_subagent_task" in payloadJson ||
            "\"mcp\"" in payloadJson ||
            "web_search" in payloadJson ||
            "image_generation" in payloadJson ||
            "tool_search" in payloadJson
        )

internal fun rewriteLegacyCommitsForAgent(
    agentId: Long,
    rawAgentCommits: List<CommitRecord>,
    path: Path,
    externalMaxIndex: Long? = null,
    externalHasCleanEventAtIndex: ((Long) -> Boolean)? = null,
): List<CommitRecord> {
    if (rawAgentCommits.isEmpty()) return emptyList()
    val resultByKey = linkedMapOf<Pair<Long, String>, CommitRecord>()
    fun putCommit(record: CommitRecord) {
        resultByKey[record.index to record.type] = record
    }

    val currentAskEntries = mutableListOf<AskUserEntry>()
    var nextAskId = 1
    for (commit in rawAgentCommits) {
        if (commit.type == AgentCommitType.AskUser.wireName) {
            val snapshot = decodeExistingAskSnapshot(commit, path)
            val maxExisting = snapshot.entries.maxOfOrNull { it.askId } ?: 0
            if (maxExisting >= nextAskId) nextAskId = maxExisting + 1
        }
    }

    val answeredAskByCallId = mutableMapOf<String, StableAskUserEvent>()
    val latestUnstableIndex = rawAgentCommits
        .filter { it.type == AgentCommitType.Unstable.wireName }
        .maxOfOrNull { it.index }

    var pendingTailUnstable: CommitRecord? = null

    for (commit in rawAgentCommits) {
        when (commit.type) {
            AgentCommitType.AskUser.wireName -> {
                val snapshot = decodeExistingAskSnapshot(commit, path)
                currentAskEntries.clear()
                currentAskEntries.addAll(snapshot.entries)
                putCommit(commit)
            }

            "image_view_tool_event" -> {
                val converted = convertImageViewCommit(commit, path)
                putCommit(converted)
            }

            "request_user_input_tool_event" -> {
                val obj = parseCommitObject(commit, path)
                val callId = obj.requireString("call_id", commit, path)
                val argsElement = obj["arguments"] ?: JsonObject(emptyMap())
                val resultObj = obj["result"]?.jsonObject
                    ?: throw IOException("Missing result in request_user_input_tool_event at index=${commit.index} in $path")
                when (val resultType = resultObj.requireString("type", commit, path)) {
                    "answered" -> {
                        val arguments = OpenAiJsonCodec.decodeFromJsonElement(RequestUserInputArgs.serializer(), argsElement)
                        val responseElement = resultObj["response"]
                            ?: throw IOException("Missing response in answered request_user_input_tool_event at index=${commit.index} in $path")
                        val response = OpenAiJsonCodec.decodeFromJsonElement(RequestUserInputResponse.serializer(), responseElement)
                        val instant = Instant.parse(commit.createdAt)
                        val askId = nextAskId++
                        currentAskEntries += AskUserEntry(
                            askId = askId,
                            questions = arguments,
                            answer = AskUserAnswer(response = response, answeredAt = instant),
                            createdAt = instant,
                        )
                        val askEvent = StableAskUserEvent(
                            askId = askId,
                            arguments = arguments,
                            response = response,
                        )
                        answeredAskByCallId[callId] = askEvent
                        putCommit(
                            CommitRecord(
                                agentId = agentId,
                                type = AgentCommitType.AskUser.wireName,
                                index = commit.index,
                                payloadJson = AskUserSnapshot(currentAskEntries.toList()).encodePayloadJson(OpenAiJsonCodec),
                                createdAt = commit.createdAt,
                            ),
                        )
                        putCommit(
                            CommitRecord(
                                agentId = agentId,
                                type = AgentCommitType.EventAskUser.wireName,
                                index = commit.index,
                                payloadJson = askEvent.encodePayloadJson(OpenAiJsonCodec),
                                createdAt = commit.createdAt,
                            ),
                        )
                    }

                    "failure" -> {
                        val message = resultObj["message"]?.jsonPrimitive?.contentOrNull ?: "request_user_input failed"
                        val textEvent = StableTextToolEvent(
                            callId = callId,
                            itemId = null,
                            name = "request_user_input",
                            namespace = null,
                            arguments = argsElement,
                            result = message,
                            success = false,
                        )
                        putCommit(
                            CommitRecord(
                                agentId = agentId,
                                type = AgentCommitType.TextToolEvent.wireName,
                                index = commit.index,
                                payloadJson = textEvent.encodePayloadJson(OpenAiJsonCodec),
                                createdAt = commit.createdAt,
                            ),
                        )
                    }

                    else -> throw IOException("Unknown request_user_input result type '$resultType' in $path")
                }
            }

            "suggest_subagent_task_tool_event" -> putCommit(convertSuggestSubagentCommit(commit, path))
            "mcp_tool_event" -> putCommit(convertMcpCommit(commit, path))
            "web_search_tool_event" -> putCommit(convertWebSearchCommit(commit, path))
            "image_generation_tool_event" -> putCommit(convertImageGenerationCommit(commit, path))
            "tool_search_event" -> putCommit(convertToolSearchCommit(commit, path))
            AgentCommitType.InvalidToolCall.wireName -> putCommit(rewriteInvalidToolCallCommit(commit, path))

            AgentCommitType.ContextCompaction.wireName -> {
                putCommit(
                    rewriteCompactionRetainedItemsJson(
                        commit = commit,
                        answeredAskByCallId = answeredAskByCallId,
                        allocateAskId = { nextAskId++ },
                        path = path,
                    ),
                )
            }

            AgentCommitType.Unstable.wireName -> {
                if (commit.index == latestUnstableIndex) {
                    pendingTailUnstable = commit
                } else {
                    putCommit(rewriteHistoricalUnstableCommit(commit, path))
                }
            }

            else -> putCommit(commit)
        }
    }

    if (pendingTailUnstable != null) {
        var maxIndex = externalMaxIndex ?: (resultByKey.keys.maxOfOrNull { it.first } ?: pendingTailUnstable.index)
        val localMax = resultByKey.keys.maxOfOrNull { it.first } ?: pendingTailUnstable.index
        if (localMax > maxIndex) maxIndex = localMax
        if (pendingTailUnstable.index > maxIndex) {
            maxIndex = pendingTailUnstable.index
        }
        applyTailUnstableMigration(
            agentId = agentId,
            tailCommit = pendingTailUnstable,
            currentAskEntries = currentAskEntries,
            allocateAskId = { nextAskId++ },
            currentMaxIndex = maxIndex,
            putCommit = ::putCommit,
            hasCleanEventAtIndex = { idx ->
                AgentCommitType.CleanEventTypes.any { (idx to it.wireName) in resultByKey } ||
                    (externalHasCleanEventAtIndex?.invoke(idx) == true)
            },
            path = path,
        )
    }

    return resultByKey.values.sortedWith(compareBy<CommitRecord> { it.index }.thenBy { it.type })
}

private fun decodeExistingAskSnapshot(commit: CommitRecord, path: Path): AskUserSnapshot =
    try {
        AgentCommitType.AskUser.decodePayloadJson(commit.payloadJson, OpenAiJsonCodec) as AskUserSnapshot
    } catch (failure: Throwable) {
        throw IOException("Invalid ask_user commit at agent=${commit.agentId}, index=${commit.index} in $path", failure)
    }

private fun parseCommitObject(commit: CommitRecord, path: Path): JsonObject =
    try {
        OpenAiJsonCodec.parseToJsonElement(commit.payloadJson) as? JsonObject
            ?: throw IOException("Expected JSON object for ${commit.type}")
    } catch (failure: Throwable) {
        throw IOException(
            "Invalid ${commit.type} JSON at agent=${commit.agentId}, index=${commit.index} in $path",
            failure,
        )
    }

private fun JsonObject.requireString(key: String, commit: CommitRecord, path: Path): String =
    this[key]?.jsonPrimitive?.contentOrNull
        ?: throw IOException("Missing '$key' in ${commit.type} at agent=${commit.agentId}, index=${commit.index} in $path")

private fun JsonObject.optionalItemId(): ResponseItemId? =
    this["item_id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull?.let(::ResponseItemId)

private fun parseMediaDetailOrNull(raw: String?): MediaDetail? =
    when (raw) {
        null -> null
        "original" -> MediaDetail.Original
        "high" -> MediaDetail.High
        else -> MediaDetail.High
    }

private fun convertImageViewCommit(commit: CommitRecord, path: Path): CommitRecord {
    val obj = parseCommitObject(commit, path)
    val callId = obj.requireString("call_id", commit, path)
    val itemId = obj.optionalItemId()
    val argsObj = obj["arguments"]?.jsonObject
        ?: throw IOException("Missing arguments in image_view_tool_event at index=${commit.index} in $path")
    val mediaPath = argsObj["path"]?.jsonPrimitive?.contentOrNull ?: ""
    val detail = parseMediaDetailOrNull(argsObj["detail"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull)
    val resultObj = obj["result"]?.jsonObject
        ?: throw IOException("Missing result in image_view_tool_event at index=${commit.index} in $path")
    val readResult = when (val resultType = resultObj.requireString("type", commit, path)) {
        "success" -> {
            val outputObj = resultObj["output"]?.jsonObject
                ?: throw IOException("Missing output in image_view_tool_event at index=${commit.index} in $path")
            val imageUrl = outputObj["image_url"]?.jsonPrimitive?.contentOrNull
                ?: throw IOException("Missing image_url in image_view_tool_event at index=${commit.index} in $path")
            val outDetail = parseMediaDetailOrNull(outputObj["detail"]?.jsonPrimitive?.contentOrNull) ?: MediaDetail.High
            StableReadMediaResult.Success(ReadMediaToolOutput(imageUrl = imageUrl, detail = outDetail))
        }
        "failure" -> {
            val message = resultObj["message"]?.jsonPrimitive?.contentOrNull ?: "read_media failed"
            StableReadMediaResult.Failure(message)
        }
        else -> throw IOException("Unknown image_view_tool_event result type '$resultType' in $path")
    }
    val converted = StableReadMediaToolEvent(
        callId = callId,
        itemId = itemId,
        arguments = ReadMediaToolArguments(path = mediaPath, detail = detail),
        result = readResult,
    )
    return commit.copy(
        type = AgentCommitType.ReadMediaToolEvent.wireName,
        payloadJson = converted.encodePayloadJson(OpenAiJsonCodec),
    )
}

private fun convertSuggestSubagentCommit(commit: CommitRecord, path: Path): CommitRecord {
    val obj = parseCommitObject(commit, path)
    val callId = obj.requireString("call_id", commit, path)
    val args = obj["arguments"] ?: JsonObject(emptyMap())
    val resultObj = obj["result"]?.jsonObject
        ?: throw IOException("Missing result in suggest_subagent_task_tool_event at index=${commit.index} in $path")
    return when (val resultType = resultObj.requireString("type", commit, path)) {
        "completed" -> {
            val response = resultObj["response"] ?: resultObj
            val event = StableJsonToolEvent(
                callId = callId,
                itemId = null,
                name = "suggest_subagent_task",
                namespace = null,
                arguments = args,
                result = response,
                success = true,
            )
            commit.copy(
                type = AgentCommitType.JsonToolEvent.wireName,
                payloadJson = event.encodePayloadJson(OpenAiJsonCodec),
            )
        }
        "failure" -> {
            val message = resultObj["message"]?.jsonPrimitive?.contentOrNull ?: "suggest_subagent_task failed"
            val event = StableTextToolEvent(
                callId = callId,
                itemId = null,
                name = "suggest_subagent_task",
                namespace = null,
                arguments = args,
                result = message,
                success = false,
            )
            commit.copy(
                type = AgentCommitType.TextToolEvent.wireName,
                payloadJson = event.encodePayloadJson(OpenAiJsonCodec),
            )
        }
        else -> throw IOException("Unknown suggest_subagent_task result type '$resultType' in $path")
    }
}

private fun convertMcpCommit(commit: CommitRecord, path: Path): CommitRecord {
    val obj = parseCommitObject(commit, path)
    val callId = obj.requireString("call_id", commit, path)
    val name = obj.requireString("name", commit, path)
    val namespace = obj["namespace"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
    val args = obj["arguments"] ?: JsonObject(emptyMap())
    val result = obj["result"] ?: JsonObject(emptyMap())
    val isError = (result as? JsonObject)?.get("isError")?.takeIf { it !is JsonNull }?.jsonPrimitive?.booleanOrNull ?: false
    val event = StableJsonToolEvent(
        callId = callId,
        itemId = null,
        name = name,
        namespace = namespace,
        arguments = args,
        result = result,
        success = !isError,
    )
    return commit.copy(
        type = AgentCommitType.JsonToolEvent.wireName,
        payloadJson = event.encodePayloadJson(OpenAiJsonCodec),
    )
}

private fun convertWebSearchCommit(commit: CommitRecord, path: Path): CommitRecord {
    val obj = parseCommitObject(commit, path)
    val callId = obj.requireString("call_id", commit, path)
    val commands = obj["commands"] ?: obj["arguments"] ?: JsonObject(emptyMap())
    val resultObj = obj["result"]?.jsonObject
        ?: throw IOException("Missing result in web_search_tool_event at index=${commit.index} in $path")
    return when (val resultType = resultObj.requireString("type", commit, path)) {
        "success" -> {
            val response = resultObj["response"] ?: resultObj
            val event = StableJsonToolEvent(
                callId = callId,
                itemId = null,
                name = "run",
                namespace = "web",
                arguments = commands,
                result = response,
                success = true,
            )
            commit.copy(
                type = AgentCommitType.JsonToolEvent.wireName,
                payloadJson = event.encodePayloadJson(OpenAiJsonCodec),
            )
        }
        "failure" -> {
            val message = resultObj["message"]?.jsonPrimitive?.contentOrNull ?: "web.run failed"
            val event = StableTextToolEvent(
                callId = callId,
                itemId = null,
                name = "run",
                namespace = "web",
                arguments = commands,
                result = message,
                success = false,
            )
            commit.copy(
                type = AgentCommitType.TextToolEvent.wireName,
                payloadJson = event.encodePayloadJson(OpenAiJsonCodec),
            )
        }
        else -> throw IOException("Unknown web_search_tool_event result type '$resultType' in $path")
    }
}

private fun convertImageGenerationCommit(commit: CommitRecord, path: Path): CommitRecord {
    val obj = parseCommitObject(commit, path)
    val callId = obj.requireString("call_id", commit, path)
    val args = obj["arguments"] ?: JsonObject(emptyMap())
    val resultObj = obj["result"]?.jsonObject
        ?: throw IOException("Missing result in image_generation_tool_event at index=${commit.index} in $path")
    return when (val resultType = resultObj.requireString("type", commit, path)) {
        "success" -> {
            val event = StableJsonToolEvent(
                callId = callId,
                itemId = null,
                name = "imagegen",
                namespace = "image_gen",
                arguments = args,
                result = resultObj,
                success = true,
            )
            commit.copy(
                type = AgentCommitType.JsonToolEvent.wireName,
                payloadJson = event.encodePayloadJson(OpenAiJsonCodec),
            )
        }
        "failure" -> {
            val message = resultObj["message"]?.jsonPrimitive?.contentOrNull ?: "imagegen failed"
            val event = StableTextToolEvent(
                callId = callId,
                itemId = null,
                name = "imagegen",
                namespace = "image_gen",
                arguments = args,
                result = message,
                success = false,
            )
            commit.copy(
                type = AgentCommitType.TextToolEvent.wireName,
                payloadJson = event.encodePayloadJson(OpenAiJsonCodec),
            )
        }
        else -> throw IOException("Unknown image_generation_tool_event result type '$resultType' in $path")
    }
}

private fun convertToolSearchCommit(commit: CommitRecord, path: Path): CommitRecord {
    val obj = parseCommitObject(commit, path)
    val callId = obj.requireString("call_id", commit, path)
    val args = obj["arguments"] ?: JsonObject(emptyMap())
    val result = obj["result"] ?: JsonObject(emptyMap())
    val success = (result as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "success"
    val event = StableJsonToolEvent(
        callId = callId,
        itemId = null,
        name = "tool_search",
        namespace = null,
        arguments = args,
        result = result,
        success = success,
    )
    return commit.copy(
        type = AgentCommitType.JsonToolEvent.wireName,
        payloadJson = event.encodePayloadJson(OpenAiJsonCodec),
    )
}

private fun rewriteToolSearchInvocationObject(obj: JsonObject): JsonObject {
    val invocation = obj["invocation"] as? JsonObject ?: return obj
    if (invocation["type"]?.jsonPrimitive?.contentOrNull != "tool_search") return obj
    val rawArgs = invocation["arguments"]
    val encodedArgs = when {
        rawArgs == null || rawArgs is JsonNull -> ""
        rawArgs is JsonPrimitive && rawArgs.isString -> rawArgs.content
        else -> OpenAiJsonCodec.encodeToString(JsonElement.serializer(), rawArgs)
    }
    val rewrittenInvocation = JsonObject(
        mapOf(
            "type" to JsonPrimitive("function"),
            "name" to JsonPrimitive("tool_search"),
            "arguments" to JsonPrimitive(encodedArgs),
        ),
    )
    return JsonObject(obj + ("invocation" to rewrittenInvocation))
}

private fun rewriteInvalidToolCallCommit(commit: CommitRecord, path: Path): CommitRecord {
    if ("tool_search" !in commit.payloadJson) return commit
    val obj = parseCommitObject(commit, path)
    val rewritten = rewriteToolSearchInvocationObject(obj)
    if (rewritten === obj) return commit
    return commit.copy(payloadJson = OpenAiJsonCodec.encodeToString(JsonObject.serializer(), rewritten))
}

private fun rewriteCompactionRetainedItemsJson(
    commit: CommitRecord,
    answeredAskByCallId: MutableMap<String, StableAskUserEvent>,
    allocateAskId: () -> Int,
    path: Path,
): CommitRecord {
    val obj = parseCommitObject(commit, path)
    val retainedArray = obj["retained_items"]?.takeIf { it !is JsonNull }?.jsonArray ?: return commit
    var changed = false
    val rewrittenItems = buildList<JsonElement> {
        for (element in retainedArray) {
            val itemObj = element as? JsonObject ?: run {
                add(element)
                continue
            }
            when (itemObj["type"]?.jsonPrimitive?.contentOrNull) {
                "request_user_input_tool_event" -> {
                    changed = true
                    val callId = itemObj["call_id"]?.jsonPrimitive?.contentOrNull
                    val existing = callId?.let(answeredAskByCallId::get)
                    if (existing != null) {
                        add(OpenAiJsonCodec.encodeToJsonElement(StableAskUserEvent.serializer(), existing))
                    } else {
                        val resultObj = itemObj["result"] as? JsonObject
                        if (resultObj?.get("type")?.jsonPrimitive?.contentOrNull == "answered") {
                            val args = OpenAiJsonCodec.decodeFromJsonElement(
                                RequestUserInputArgs.serializer(),
                                itemObj["arguments"] ?: JsonObject(emptyMap()),
                            )
                            val response = OpenAiJsonCodec.decodeFromJsonElement(
                                RequestUserInputResponse.serializer(),
                                resultObj["response"] ?: JsonObject(emptyMap()),
                            )
                            val synthesized = StableAskUserEvent(
                                askId = allocateAskId(),
                                arguments = args,
                                response = response,
                            )
                            if (callId != null) answeredAskByCallId[callId] = synthesized
                            add(OpenAiJsonCodec.encodeToJsonElement(StableAskUserEvent.serializer(), synthesized))
                        }
                    }
                }
                "suggest_subagent_task_tool_event" -> {
                    changed = true
                }
                else -> add(element)
            }
        }
    }
    if (!changed) return commit
    val updatedObj = JsonObject(obj + ("retained_items" to JsonArray(rewrittenItems)))
    return commit.copy(payloadJson = OpenAiJsonCodec.encodeToString(JsonObject.serializer(), updatedObj))
}

private data class RetiredPendingToolInfo(
    val callId: String,
    val name: String,
    val namespace: String?,
    val arguments: JsonElement,
    val askQuestions: RequestUserInputArgs? = null,
)

private fun extractRetiredPendingToolInfo(itemObj: JsonObject): RetiredPendingToolInfo? {
    val type = itemObj["type"]?.jsonPrimitive?.contentOrNull ?: return null
    val callId = itemObj["call_id"]?.jsonPrimitive?.contentOrNull ?: return null
    return when (type) {
        "request_user_input" -> {
            val argsElement = itemObj["arguments"] ?: JsonObject(emptyMap())
            val parsedQuestions = OpenAiJsonCodec.decodeFromJsonElement(RequestUserInputArgs.serializer(), argsElement)
            RetiredPendingToolInfo(
                callId = callId,
                name = "request_user_input",
                namespace = null,
                arguments = argsElement,
                askQuestions = parsedQuestions,
            )
        }
        "suggest_subagent_task" -> RetiredPendingToolInfo(
            callId = callId,
            name = "suggest_subagent_task",
            namespace = null,
            arguments = itemObj["arguments"] ?: JsonObject(emptyMap()),
        )
        "mcp" -> RetiredPendingToolInfo(
            callId = callId,
            name = itemObj["name"]?.jsonPrimitive?.contentOrNull ?: "mcp",
            namespace = itemObj["namespace"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull,
            arguments = itemObj["arguments"] ?: JsonObject(emptyMap()),
        )
        "web_search" -> RetiredPendingToolInfo(
            callId = callId,
            name = "run",
            namespace = "web",
            arguments = itemObj["commands"] ?: itemObj["arguments"] ?: JsonObject(emptyMap()),
        )
        "image_generation" -> RetiredPendingToolInfo(
            callId = callId,
            name = "imagegen",
            namespace = "image_gen",
            arguments = itemObj["arguments"] ?: JsonObject(emptyMap()),
        )
        "tool_search" -> RetiredPendingToolInfo(
            callId = callId,
            name = "tool_search",
            namespace = null,
            arguments = itemObj["arguments"] ?: JsonObject(emptyMap()),
        )
        else -> null
    }
}

private fun rewriteCommonUnstableElement(element: JsonElement): Pair<JsonElement, Boolean> {
    val obj = element as? JsonObject ?: return element to false
    return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
        "image_view" -> {
            val callId = obj["call_id"]?.jsonPrimitive?.contentOrNull ?: ""
            val itemId = obj.optionalItemId()
            val argsObj = obj["arguments"] as? JsonObject ?: JsonObject(emptyMap())
            val mediaPath = argsObj["path"]?.jsonPrimitive?.contentOrNull ?: ""
            val detail = parseMediaDetailOrNull(argsObj["detail"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull)
            val converted = PendingReadMediaToolEvent(
                callId = callId,
                itemId = itemId,
                arguments = ReadMediaToolArguments(path = mediaPath, detail = detail),
            )
            OpenAiJsonCodec.encodeToJsonElement(UnstableCleanEvent.serializer(), converted) to true
        }
        "invalid_tool_call" -> {
            val rewritten = rewriteToolSearchInvocationObject(obj)
            rewritten to (rewritten !== obj)
        }
        else -> obj to false
    }
}

private fun parseUnstableArray(commit: CommitRecord, path: Path): JsonArray =
    try {
        OpenAiJsonCodec.parseToJsonElement(commit.payloadJson) as? JsonArray
            ?: throw IOException("Expected JSON array for unstable commit")
    } catch (failure: Throwable) {
        throw IOException("Invalid unstable commit at agent=${commit.agentId}, index=${commit.index} in $path", failure)
    }

private fun rewriteHistoricalUnstableCommit(commit: CommitRecord, path: Path): CommitRecord {
    if (!mayContainRetiredUnstableItem(commit.payloadJson)) return commit
    val rawArray = parseUnstableArray(commit, path)
    var changed = false
    val updatedElements = rawArray.map { element ->
        val (commonRewritten, commonChanged) = rewriteCommonUnstableElement(element)
        if (commonChanged) {
            changed = true
            return@map commonRewritten
        }
        val obj = commonRewritten as? JsonObject ?: return@map commonRewritten
        val retired = extractRetiredPendingToolInfo(obj) ?: return@map commonRewritten
        changed = true
        val fallback = PendingFunctionToolEvent(
            callId = retired.callId,
            itemId = null,
            name = retired.name,
            namespace = retired.namespace,
            arguments = retired.arguments,
        )
        OpenAiJsonCodec.encodeToJsonElement(UnstableCleanEvent.serializer(), fallback)
    }
    if (!changed) return commit
    return commit.copy(payloadJson = OpenAiJsonCodec.encodeToString(JsonArray.serializer(), JsonArray(updatedElements)))
}

private fun applyTailUnstableMigration(
    agentId: Long,
    tailCommit: CommitRecord,
    currentAskEntries: MutableList<AskUserEntry>,
    allocateAskId: () -> Int,
    currentMaxIndex: Long,
    putCommit: (CommitRecord) -> Unit,
    hasCleanEventAtIndex: (Long) -> Boolean,
    path: Path,
) {
    if (!mayContainRetiredUnstableItem(tailCommit.payloadJson)) {
        putCommit(tailCommit)
        return
    }
    val rawArray = parseUnstableArray(tailCommit, path)
    val keptElements = mutableListOf<JsonElement>()
    val retiredPending = mutableListOf<RetiredPendingToolInfo>()
    var changed = false

    for (element in rawArray) {
        val (commonRewritten, commonChanged) = rewriteCommonUnstableElement(element)
        if (commonChanged) changed = true
        val obj = commonRewritten as? JsonObject
        val retired = obj?.let(::extractRetiredPendingToolInfo)
        if (retired != null) {
            changed = true
            retiredPending += retired
        } else {
            keptElements += commonRewritten
        }
    }

    if (retiredPending.isEmpty()) {
        if (changed) {
            putCommit(
                tailCommit.copy(
                    payloadJson = OpenAiJsonCodec.encodeToString(JsonArray.serializer(), JsonArray(keptElements)),
                ),
            )
        } else {
            putCommit(tailCommit)
        }
        return
    }

    // Preserve a valid historical unstable snapshot at tailCommit.index if new completion indices follow.
    putCommit(rewriteHistoricalUnstableCommit(tailCommit, path))

    val validKeptUnstableJson = OpenAiJsonCodec.encodeToString(
        ListSerializer(UnstableCleanEvent.serializer()),
        keptElements.map { OpenAiJsonCodec.decodeFromJsonElement(UnstableCleanEvent.serializer(), it) },
    )

    val instant = Instant.parse(tailCommit.createdAt)
    var cursorIndex = tailCommit.index

    for ((i, retired) in retiredPending.withIndex()) {
        val targetIndex = if (i == 0 && tailCommit.index >= currentMaxIndex && !hasCleanEventAtIndex(tailCommit.index)) {
            tailCommit.index
        } else {
            maxOf(cursorIndex, currentMaxIndex) + 1
        }
        cursorIndex = targetIndex

        if (retired.askQuestions != null) {
            currentAskEntries += AskUserEntry(
                askId = allocateAskId(),
                questions = retired.askQuestions,
                answer = null,
                createdAt = instant,
            )
            putCommit(
                CommitRecord(
                    agentId = agentId,
                    type = AgentCommitType.AskUser.wireName,
                    index = targetIndex,
                    payloadJson = AskUserSnapshot(currentAskEntries.toList()).encodePayloadJson(OpenAiJsonCodec),
                    createdAt = tailCommit.createdAt,
                ),
            )
        }

        val interrupted = StableTextToolEvent(
            callId = retired.callId,
            itemId = null,
            name = retired.name,
            namespace = retired.namespace,
            arguments = retired.arguments,
            result = InterruptedByV0_7_2Message,
            success = false,
        )
        putCommit(
            CommitRecord(
                agentId = agentId,
                type = AgentCommitType.TextToolEvent.wireName,
                index = targetIndex,
                payloadJson = interrupted.encodePayloadJson(OpenAiJsonCodec),
                createdAt = tailCommit.createdAt,
            ),
        )
        putCommit(
            CommitRecord(
                agentId = agentId,
                type = AgentCommitType.Unstable.wireName,
                index = targetIndex,
                payloadJson = validKeptUnstableJson,
                createdAt = tailCommit.createdAt,
            ),
        )
    }
}
