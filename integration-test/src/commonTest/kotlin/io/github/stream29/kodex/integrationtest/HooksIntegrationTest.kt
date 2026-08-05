package io.github.stream29.kodex.integrationtest

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentsession.filesystem.FileSystemKodexSessionRepository
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.agentstate.test.TestAgentContextSettings
import io.github.stream29.kodex.agentstate.test.TestMcpService
import io.github.stream29.kodex.cli.auth.InMemoryKodexAuthStore
import io.github.stream29.kodex.hook.contract.HookConfiguration
import io.github.stream29.kodex.hook.contract.HookSettings
import io.github.stream29.kodex.hook.impl.KodexHooksImpl
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.client.OpenAiClient as RealOpenAiClient
import io.github.stream29.kodex.openai.client.OpenAiClientConfig
import io.github.stream29.kodex.openai.codexclistorage.CodexCliHookSourceKind
import io.github.stream29.kodex.openai.codexclistorage.CodexCliStorage
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.github.stream29.kodex.utils.osenvironment.userHomeDirectory
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val HookIntegrationMarker: String = "KODEX_HOOK_INTEGRATION_OK"

private data class IntegrationHookSettings(
    override val hooks: HookConfiguration,
) : HookSettings

private suspend fun kodexHooks(configuration: HookConfiguration): KodexHooksImpl =
    CoroutineScope(currentCoroutineContext())
        .KodexHooksImpl(MutableStateFlow(IntegrationHookSettings(configuration)))

val openAiFreshSessionHookIntegrationTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("fresh filesystem session runs Codex Home hooks through natural stop") {
        withTimeout(180_000L) {
            runFreshSessionHookIntegration()
        }
    }
}

private suspend fun runFreshSessionHookIntegration() {
    val root = Path(
        SystemTemporaryDirectory,
        "kodex-hook-integration-${Random.nextLong().toString().replace('-', '0')}",
    )
    val codexHome = Path(root, "codex-home")
    val kodexHome = Path(root, "kodex-home")
    val workingDirectory = Path(root, "workspace")
    val hookLog = Path(codexHome, "hook-events.jsonl")
    var client: RealOpenAiClient? = null
    var modelCatalog: OpenAiModelCatalog? = null
    var hooks: KodexHooksImpl? = null
    var sessionRepository: FileSystemKodexSessionRepository? = null
    try {
        SystemCoroutineFileSystem.createDirectories(codexHome)
        SystemCoroutineFileSystem.createDirectories(kodexHome)
        SystemCoroutineFileSystem.createDirectories(workingDirectory)

        val sourceCodexHome = configuredIntegrationCodexHome()
        copyCodexRuntimeFiles(sourceCodexHome, codexHome)
        val model = integrationModel(CodexCliStorage(sourceCodexHome))
        installHooks(
            codexHome = codexHome,
            model = model,
            command = recordingHookCommand(hookLog, "{}"),
        )

        val codexStorage = CodexCliStorage(codexHome)
        client = realOpenAiClient(codexStorage)
        modelCatalog = OpenAiModelCatalog(client, codexStorage)
        hooks = kodexHooks(loadHookConfiguration(codexHome))
        val dependencies = KodexAgentDependencies(
            client = client,
            modelCatalog = modelCatalog,
            contextSettings = TestAgentContextSettings,
            shellSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
            hooks = hooks,
        )

        sessionRepository = CoroutineScope(currentCoroutineContext())
            .FileSystemKodexSessionRepository(kodexHome, dependencies)
        val sessionIndex = sessionRepository.create()
        val session = sessionRepository.open(sessionIndex)
        val settings = KodexAgentSettings(
            model = model,
            cwd = workingDirectory,
            instructions = "Do not call tools. Reply with only the exact marker requested by the user.",
        )
        session.runtime.modify { storage -> storage.initialize(settings) }
        assertEquals(0, session.storage.tokenCount.latestIndex())
        assertEquals(0L, session.storage.tokenCount[0])

        val hookSessionId = session.storage.id
        val runtime = session.runtime

        val prompt = "Reply with exactly $HookIntegrationMarker and no other text."
        runtime.appendUserMessage(listOf(ContentItem.InputText(prompt)))
        runtime.resume()
        assertIs<KodexAgentStateValue.AssistantMessage>(runtime.state.value)

        val requests = readHookRequests(hookLog)
        assertEquals(
            listOf("UserPromptSubmit", "Stop"),
            requests.map { request -> request.getValue("hook_event_name").jsonPrimitive.content },
        )
        assertEquals(
            setOf(hookSessionId),
            requests.map { request -> request.getValue("session_id").jsonPrimitive.content }.toSet(),
        )
        assertEquals(prompt, requests[0].getValue("prompt").jsonPrimitive.content)
        assertEquals(false, requests[1].getValue("stop_hook_active").jsonPrimitive.boolean)
        assertTrue(
            requests[1].getValue("last_assistant_message").jsonPrimitive.content.contains(HookIntegrationMarker),
        )
    } finally {
        hooks?.cancel()
        sessionRepository?.closeAndJoin()
        modelCatalog?.close()
        client?.close()
        root.deleteRecursively()
    }
}

private fun configuredIntegrationCodexHome(): Path =
    environmentVariable("CODEX_HOME")
        ?.takeIf(String::isNotBlank)
        ?.let(::Path)
        ?: userHomeDirectory()?.let { home -> Path(home, ".codex") }
        ?: error("CODEX_HOME or a readable user home directory is required.")

private suspend fun copyCodexRuntimeFiles(source: Path, destination: Path) {
    val auth = Path(source, "auth.json")
    SystemCoroutineFileSystem.writeBytes(
        Path(destination, "auth.json"),
        SystemCoroutineFileSystem.readBytes(auth),
    )
    val modelsCache = Path(source, "models_cache.json")
    if (SystemCoroutineFileSystem.exists(modelsCache)) {
        SystemCoroutineFileSystem.writeBytes(
            Path(destination, "models_cache.json"),
            SystemCoroutineFileSystem.readBytes(modelsCache),
        )
    }
}

private suspend fun integrationModel(storage: CodexCliStorage): OpenAiModelId {
    val configured = storage.readConfigTomlOrNull()?.model
    val cached = storage.readModelsCacheOrNull()?.models.orEmpty()
    return OpenAiModelId(
        configured
            ?: cached.firstOrNull { model -> model.slug.value.contains("codex", ignoreCase = true) }?.slug?.value
            ?: cached.firstOrNull()?.slug?.value
            ?: error("Codex CLI models_cache.json must contain at least one model."),
    )
}

private suspend fun realOpenAiClient(storage: CodexCliStorage): RealOpenAiClient {
    val tokens = requireNotNull(storage.readAuthOrNull()?.tokens) {
        "Codex CLI auth.json must contain tokens."
    }
    val clientVersion = storage.readModelsCacheOrNull()?.clientVersion
        ?.takeIf { version -> version.matches(Regex("""\d+\.\d+\.\d+""")) }
        ?: "0.1.0"
    return RealOpenAiClient(
        authStore = InMemoryKodexAuthStore(
            OpenAiSubscriptionAuthState(
                accessToken = tokens.accessToken,
                accountId = tokens.accountId?.takeIf(String::isNotBlank),
            ),
        ),
        config = OpenAiClientConfig(clientVersion = clientVersion),
    )
}

private suspend fun installHooks(
    codexHome: Path,
    model: OpenAiModelId,
    command: String,
) {
    val hooksPath = Path(codexHome, "hooks.json")
    val contents = buildJsonObject {
        put("hooks", buildJsonObject {
            listOf("UserPromptSubmit", "Stop").forEach { eventName ->
                put(eventName, buildJsonArray {
                    add(buildJsonObject {
                        put("hooks", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "command")
                                put("command", command)
                                put("timeout", 5)
                            })
                        })
                    })
                })
            }
        })
    }.toString()
    val config = buildString {
        append("model = \"")
        append(model.value.tomlStringContent())
        appendLine("\"")
    }
    SystemCoroutineFileSystem.writeString(hooksPath, contents)
    SystemCoroutineFileSystem.writeString(Path(codexHome, "config.toml"), config)
}

private suspend fun loadHookConfiguration(codexHome: Path): HookConfiguration {
    return HookConfiguration(
        sources = CodexCliStorage(codexHome).readHookLayers(
            sourceKind = CodexCliHookSourceKind.User,
        ),
    )
}

private fun recordingHookCommand(logPath: Path, output: String): String = when (Shell.default.type) {
    ShellType.Sh,
    ShellType.Bash,
    ShellType.Zsh,
        -> "body=\$(cat); printf '%s\\n' \"\$body\" >> ${logPath.toString().posixShellArgument()}; " +
        "printf '%s' ${output.posixShellArgument()}"

    ShellType.PowerShell ->
        "\$body = [Console]::In.ReadToEnd(); " +
            "[IO.File]::AppendAllText('${logPath.toString().powerShellStringContent()}', " +
            "\$body + [Environment]::NewLine); " +
            "[Console]::Out.Write('${output.powerShellStringContent()}')"

    ShellType.Cmd -> "more >> \"$logPath\" & <nul set /p \"=$output\""
}

private suspend fun readHookRequests(path: Path): List<JsonObject> =
    SystemCoroutineFileSystem.readString(path)
        .lineSequence()
        .filter(String::isNotBlank)
        .map { line -> Json.parseToJsonElement(line).jsonObject }
        .toList()

private fun String.posixShellArgument(): String = "'${replace("'", "'\"'\"'")}'"

private fun String.powerShellStringContent(): String = replace("'", "''")

private fun String.tomlStringContent(): String = replace("\\", "\\\\").replace("\"", "\\\"")

private suspend fun FileSystemKodexSessionRepository.closeAndJoin() {
    cancel()
    coroutineContext[Job]?.join()
}

private suspend fun Path.deleteRecursively() {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(this) ?: return
    if (metadata.isDirectory) {
        for (child in SystemCoroutineFileSystem.list(this)) {
            child.deleteRecursively()
        }
    }
    SystemCoroutineFileSystem.delete(this, mustExist = false)
}
