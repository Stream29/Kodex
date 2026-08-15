package io.github.stream29.kodex.openai.codexclistorage

import dev.eav.tomlkt.Toml
import dev.eav.tomlkt.TomlArray
import dev.eav.tomlkt.TomlTable
import io.github.stream29.kodex.hook.contract.DefaultHookTimeoutSeconds
import io.github.stream29.kodex.hook.contract.HookCodexImportCandidate
import io.github.stream29.kodex.hook.contract.HookCodexImportIdentity
import io.github.stream29.kodex.hook.contract.HookCodexImportTemplate
import io.github.stream29.kodex.hook.contract.HookCodexSourceKind
import io.github.stream29.kodex.hook.contract.HookCommandDefinition
import io.github.stream29.kodex.hook.contract.HookDeclarations
import io.github.stream29.kodex.hook.contract.HookEnvironmentValue
import io.github.stream29.kodex.hook.contract.HookEvent
import io.github.stream29.kodex.hook.contract.HookMatcher
import io.github.stream29.kodex.hook.contract.HookMatcherGroup
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal suspend fun CodexCliStorage.readHookImportCandidatesInternal(
    sourceKind: HookCodexSourceKind,
    environment: Map<String, String>,
): List<HookCodexImportCandidate> {
    val hooksPath = Path(directory, CodexHooksFileName)
    val configPath = Path(directory, CodexConfigFileName)
    return listOfNotNull(
        readHookImportCandidateOrNull(
            path = hooksPath,
            sourceKind = sourceKind,
            environment = environment,
            format = CodexHookSourceFormat.Json,
        ),
        readHookImportCandidateOrNull(
            path = configPath,
            sourceKind = sourceKind,
            environment = environment,
            format = CodexHookSourceFormat.Toml,
        ),
    )
}

private suspend fun CodexCliStorage.readHookImportCandidateOrNull(
    path: Path,
    sourceKind: HookCodexSourceKind,
    environment: Map<String, String>,
    format: CodexHookSourceFormat,
): HookCodexImportCandidate? {
    if (!fileSystem.exists(path)) return null
    val normalizedPath = fileSystem.resolve(path).toString()
    val identity = HookCodexImportIdentity(
        sourceKind = sourceKind,
        normalizedPath = normalizedPath,
    )
    val fallbackName = "${sourceKind.displayName()} ${path.name}"
    return try {
        val contents = fileSystem.readString(path)
        val parsed = when (format) {
            CodexHookSourceFormat.Json -> {
                val root = CodexHookImportJson.parseToJsonElement(contents)
                DecodedCodexHookDocument(
                    document = CodexHookImportJson.decodeFromJsonElement(
                        CodexHookImportDocument.serializer(),
                        root,
                    ),
                    excludedDetails = root.unsupportedJsonHookDetails(),
                )
            }

            CodexHookSourceFormat.Toml -> {
                val root = CodexHookImportToml.parseToTomlTable(contents)
                if ("hooks" !in root) return null
                DecodedCodexHookDocument(
                    document = CodexHookImportToml.decodeFromTomlElement(
                        CodexHookImportDocument.serializer(),
                        root,
                    ),
                    excludedDetails = root.unsupportedTomlHookDetails(),
                )
            }
        }
        parsed.toCandidate(
            identity = identity,
            fallbackName = fallbackName,
            environment = environment,
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        HookCodexImportCandidate.Unsupported(
            identity = identity,
            displayName = fallbackName,
            detail = "The source could not be decoded as Codex Hooks.",
        )
    }
}

private fun DecodedCodexHookDocument.toCandidate(
    identity: HookCodexImportIdentity,
    fallbackName: String,
    environment: Map<String, String>,
): HookCodexImportCandidate {
    val exclusions = excludedDetails.toMutableList()
    val declarations = document.hooks.toKodexDeclarations(environment, exclusions)
    val displayName = document.description
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: fallbackName
    if (declarations.commandCount == 0) {
        return HookCodexImportCandidate.Unsupported(
            identity = identity,
            displayName = displayName,
            detail = exclusions.firstOrNull()
                ?: "The source contains no supported command Hooks.",
        )
    }
    return HookCodexImportCandidate.Supported(
        identity = identity,
        displayName = displayName,
        template = HookCodexImportTemplate(
            name = displayName,
            environment = environment.mapValues { (_, value) -> HookEnvironmentValue(value) },
            hooks = declarations,
        ),
        excludedDetails = exclusions.distinct(),
    )
}

private fun CodexHookImportDeclarations.toKodexDeclarations(
    environment: Map<String, String>,
    exclusions: MutableList<String>,
): HookDeclarations {
    val sourceState = state
    val consumedStateKeys = mutableSetOf<String>()
    var declarations = HookDeclarations()
    HookEvent.entries.forEach { event ->
        declarations = declarations.withGroups(
            event = event,
            groups = groups(event).mapIndexedNotNull { groupIndex, group ->
                group.toKodexGroup(
                    event = event,
                    groupIndex = groupIndex,
                    environment = environment,
                    state = sourceState,
                    consumedStateKeys = consumedStateKeys,
                    exclusions = exclusions,
                )
            },
        )
    }
    unsupportedEventCounts().forEach { (eventName, count) ->
        if (count > 0) exclusions += "$eventName handlers are not supported."
    }
    val unmatchedState = sourceState.keys - consumedStateKeys
    if (unmatchedState.isNotEmpty()) {
        exclusions += "Unmatched Hook state entries were excluded."
    }
    if (sourceState.values.any { value -> value.trustedHash != null }) {
        exclusions += "Hook trust hashes were excluded."
    }
    return declarations
}

private fun CodexHookImportDeclarations.groups(event: HookEvent): List<CodexHookImportGroup> =
    when (event) {
        HookEvent.PreToolUse -> preToolUse
        HookEvent.PermissionRequest -> permissionRequest
        HookEvent.PostToolUse -> postToolUse
        HookEvent.PreCompact -> preCompact
        HookEvent.PostCompact -> postCompact
        HookEvent.UserPromptSubmit -> userPromptSubmit
        HookEvent.Stop -> stop
    }

private fun CodexHookImportDeclarations.unsupportedEventCounts(): List<Pair<String, Int>> =
    listOf(
        "SessionStart" to sessionStart.size,
        "SessionEnd" to sessionEnd.size,
        "SubagentStart" to subagentStart.size,
        "SubagentStop" to subagentStop.size,
    )

private fun CodexHookImportGroup.toKodexGroup(
    event: HookEvent,
    groupIndex: Int,
    environment: Map<String, String>,
    state: Map<String, CodexHookImportState>,
    consumedStateKeys: MutableSet<String>,
    exclusions: MutableList<String>,
): HookMatcherGroup? {
    val compiledMatcher = HookMatcher.parse(matcher)
    if (compiledMatcher is HookMatcher.Invalid) {
        exclusions += "${event.wireName} matcher group ${groupIndex + 1} has an invalid pattern."
        return null
    }
    val commands = hooks.mapIndexedNotNull { handlerIndex, handler ->
        handler.toKodexCommand(
            event = event,
            handlerIndex = handlerIndex,
            environment = environment,
            state = state,
            consumedStateKeys = consumedStateKeys,
            exclusions = exclusions,
        )
    }
    return commands.takeIf(List<*>::isNotEmpty)?.let { retained ->
        HookMatcherGroup(
            matcher = compiledMatcher,
            hooks = retained,
        )
    }
}

private fun CodexHookImportHandler.toKodexCommand(
    event: HookEvent,
    handlerIndex: Int,
    environment: Map<String, String>,
    state: Map<String, CodexHookImportState>,
    consumedStateKeys: MutableSet<String>,
    exclusions: MutableList<String>,
): HookCommandDefinition? {
    if (type != CodexCommandHandlerType) {
        exclusions += "${event.wireName} ${type.ifBlank { "unknown" }} handlers are not supported."
        return null
    }
    if (async) {
        exclusions += "${event.wireName} asynchronous command handlers are not supported."
        return null
    }
    val selectedCommand = if (CodexCliStoragePlatform.isWindows) {
        commandWindows ?: commandWindowsAlias ?: command
    } else {
        command
    }?.expandEnvironment(environment)
    if (selectedCommand.isNullOrBlank()) {
        exclusions += "${event.wireName} command ${handlerIndex + 1} is blank."
        return null
    }
    if (additionalContextLimit != null && additionalContextLimit < 0) {
        exclusions += "${event.wireName} command ${handlerIndex + 1} has an invalid context limit."
        return null
    }
    val persistedState = key?.let { stateKey ->
        state[stateKey]?.also { consumedStateKeys += stateKey }
    }
    if (trustedHash != null) exclusions += "Hook trust hashes were excluded."
    return HookCommandDefinition(
        command = selectedCommand,
        timeoutSeconds = (timeoutSeconds ?: DefaultHookTimeoutSeconds).coerceAtLeast(1L),
        enabled = enabled && persistedState?.enabled != false,
        statusMessage = statusMessage,
        additionalContextLimit = additionalContextLimit,
    )
}

private fun String.expandEnvironment(environment: Map<String, String>): String =
    environment.entries.fold(this) { expanded, (name, value) ->
        expanded.replace("\${$name}", value)
    }

private fun JsonElement.unsupportedJsonHookDetails(): List<String> {
    val root = this as? JsonObject ?: return emptyList()
    val details = mutableListOf<String>()
    root.keys
        .filterNot(AllowedJsonDocumentFields::contains)
        .forEach { field -> details += "Unsupported source field '$field' was excluded." }
    val declarations = root["hooks"] as? JsonObject ?: return details
    declarations.keys
        .filterNot(AllowedHookDeclarationFields::contains)
        .forEach { field -> details += "Unsupported Hook event '$field' was excluded." }
    AllowedHookEventFields.forEach { eventName ->
        (declarations[eventName] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.forEach { group ->
                group.keys
                    .filterNot(AllowedHookGroupFields::contains)
                    .forEach { field ->
                        details += "Unsupported $eventName group field '$field' was excluded."
                    }
                (group["hooks"] as? JsonArray)
                    ?.filterIsInstance<JsonObject>()
                    ?.forEach { handler ->
                        handler.keys
                            .filterNot(AllowedHookHandlerFields::contains)
                            .forEach { field ->
                                details +=
                                    "Unsupported $eventName handler field '$field' was excluded."
                            }
                    }
            }
    }
    return details
}

private fun TomlTable.unsupportedTomlHookDetails(): List<String> {
    val declarations = this["hooks"] as? TomlTable ?: return emptyList()
    val details = mutableListOf<String>()
    declarations.keys
        .filterNot(AllowedHookDeclarationFields::contains)
        .forEach { field -> details += "Unsupported Hook event '$field' was excluded." }
    AllowedHookEventFields.forEach { eventName ->
        (declarations[eventName] as? TomlArray)
            ?.filterIsInstance<TomlTable>()
            ?.forEach { group ->
                group.keys
                    .filterNot(AllowedHookGroupFields::contains)
                    .forEach { field ->
                        details += "Unsupported $eventName group field '$field' was excluded."
                    }
                (group["hooks"] as? TomlArray)
                    ?.filterIsInstance<TomlTable>()
                    ?.forEach { handler ->
                        handler.keys
                            .filterNot(AllowedHookHandlerFields::contains)
                            .forEach { field ->
                                details +=
                                    "Unsupported $eventName handler field '$field' was excluded."
                            }
                    }
            }
    }
    return details
}

@Serializable
private data class CodexHookImportDocument(
    val description: String? = null,
    val hooks: CodexHookImportDeclarations = CodexHookImportDeclarations(),
)

@Serializable
private data class CodexHookImportDeclarations(
    @SerialName("PreToolUse")
    val preToolUse: List<CodexHookImportGroup> = emptyList(),
    @SerialName("PermissionRequest")
    val permissionRequest: List<CodexHookImportGroup> = emptyList(),
    @SerialName("PostToolUse")
    val postToolUse: List<CodexHookImportGroup> = emptyList(),
    @SerialName("PreCompact")
    val preCompact: List<CodexHookImportGroup> = emptyList(),
    @SerialName("PostCompact")
    val postCompact: List<CodexHookImportGroup> = emptyList(),
    @SerialName("SessionStart")
    val sessionStart: List<CodexHookImportGroup> = emptyList(),
    @SerialName("SessionEnd")
    val sessionEnd: List<CodexHookImportGroup> = emptyList(),
    @SerialName("UserPromptSubmit")
    val userPromptSubmit: List<CodexHookImportGroup> = emptyList(),
    @SerialName("SubagentStart")
    val subagentStart: List<CodexHookImportGroup> = emptyList(),
    @SerialName("SubagentStop")
    val subagentStop: List<CodexHookImportGroup> = emptyList(),
    @SerialName("Stop")
    val stop: List<CodexHookImportGroup> = emptyList(),
    val state: Map<String, CodexHookImportState> = emptyMap(),
)

@Serializable
private data class CodexHookImportGroup(
    val matcher: String = "*",
    val hooks: List<CodexHookImportHandler> = emptyList(),
)

@Serializable
private data class CodexHookImportHandler(
    val type: String,
    val command: String? = null,
    @SerialName("commandWindows")
    val commandWindows: String? = null,
    @SerialName("command_windows")
    val commandWindowsAlias: String? = null,
    @SerialName("timeout")
    val timeoutSeconds: Long? = null,
    val async: Boolean = false,
    val enabled: Boolean = true,
    val key: String? = null,
    @SerialName("trusted_hash")
    val trustedHash: String? = null,
    @SerialName("statusMessage")
    val statusMessage: String? = null,
    @SerialName("additionalContextLimit")
    val additionalContextLimit: Int? = null,
)

@Serializable
private data class CodexHookImportState(
    val enabled: Boolean? = null,
    @SerialName("trusted_hash")
    val trustedHash: String? = null,
)

private data class DecodedCodexHookDocument(
    val document: CodexHookImportDocument,
    val excludedDetails: List<String>,
)

private enum class CodexHookSourceFormat {
    Json,
    Toml,
}

internal expect object CodexCliStoragePlatform {
    val isWindows: Boolean
}

private fun HookCodexSourceKind.displayName(): String =
    when (this) {
        HookCodexSourceKind.User -> "User"
        HookCodexSourceKind.Project -> "Project"
    }

private val CodexHookImportToml: Toml = Toml {
    ignoreUnknownKeys = true
}

private val CodexHookImportJson: Json = Json(OpenAiJsonCodec) {
    ignoreUnknownKeys = true
}

private const val CodexCommandHandlerType: String = "command"
private const val CodexHooksFileName: String = "hooks.json"
private const val CodexConfigFileName: String = "config.toml"

private val AllowedJsonDocumentFields: Set<String> = setOf("description", "hooks")
private val AllowedHookEventFields: Set<String> = setOf(
    "PreToolUse",
    "PermissionRequest",
    "PostToolUse",
    "PreCompact",
    "PostCompact",
    "SessionStart",
    "SessionEnd",
    "UserPromptSubmit",
    "SubagentStart",
    "SubagentStop",
    "Stop",
)
private val AllowedHookDeclarationFields: Set<String> =
    AllowedHookEventFields + "state"
private val AllowedHookGroupFields: Set<String> = setOf("matcher", "hooks")
private val AllowedHookHandlerFields: Set<String> = setOf(
    "type",
    "command",
    "commandWindows",
    "command_windows",
    "timeout",
    "async",
    "enabled",
    "key",
    "trusted_hash",
    "statusMessage",
    "additionalContextLimit",
)
