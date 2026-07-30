package io.github.stream29.kodex.openai.codexclistorage

import dev.eav.tomlkt.Toml
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json

public class CodexCliStorage(
    internal val directory: Path,
    internal val fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
) {
    private val authPath: Path = Path(directory, CodexAuthFileName)
    private val modelsCachePath: Path = Path(directory, CodexModelsCacheFileName)
    private val configPath: Path = Path(directory, CodexConfigFileName)
    private val hooksPath: Path = Path(directory, KodexHooksFileName)

    /**
     * @return Nullable because Codex CLI may not be signed in; `null` means
     * `auth.json` is absent.
     */
    public suspend fun readAuthOrNull(): CodexAuthJson? {
        val text = authPath.readTextOrNull(fileSystem) ?: return null
        return OpenAiJsonCodec.decodeFromString(CodexAuthJson.serializer(), text)
    }

    /**
     * @return Nullable because Codex may not have fetched a model catalog yet;
     * `null` means `models_cache.json` is absent.
     */
    public suspend fun readModelsCacheOrNull(): CodexModelsCache? {
        val text = modelsCachePath.readTextOrNull(fileSystem) ?: return null
        return OpenAiJsonCodec.decodeFromString(CodexModelsCache.serializer(), text)
    }

    /**
     * Reads supported settings from this Codex Home's `config.toml`.
     *
     * @return Nullable because Codex may use its defaults without a config
     * file; `null` means `config.toml` is absent.
     */
    public suspend fun readConfigTomlOrNull(): CodexCliConfig? {
        val text = configPath.readTextOrNull(fileSystem) ?: return null
        return CodexConfigToml.decodeFromString(CodexCliConfig.serializer(), text)
    }

    /**
     * Reads and fully decodes this directory's `hooks.json` and inline
     * `config.toml` Hook layers.
     *
     * Returned declarations retain their source structure while sealed handler
     * types and matchers are decoded into their final Kotlin models.
     */
    public suspend fun readHookLayers(
        sourceKind: CodexCliHookSourceKind,
        environment: Map<String, String> = emptyMap(),
    ): List<CodexCliHookLayer> {
        val hooksJson = hooksPath.readTextOrNull(fileSystem)?.let { contents ->
            val document = KodexHooksJson.decodeFromString(
                CodexCliHooksDocument.serializer(),
                contents,
            )
            CodexCliHookLayer(
                sourcePath = hooksPath,
                sourceKind = sourceKind,
                environment = environment,
                description = document.description,
                hooks = document.hooks,
            )
        }
        val inlineToml = configPath.readTextOrNull(fileSystem)?.let { contents ->
            val document = CodexConfigToml.decodeFromString(
                CodexCliHooksDocument.serializer(),
                contents,
            )
            CodexCliHookLayer(
                sourcePath = configPath,
                sourceKind = sourceKind,
                environment = environment,
                hooks = document.hooks,
            )
        }
        return listOfNotNull(hooksJson, inlineToml)
    }
}

private suspend fun Path.readTextOrNull(fileSystem: CoroutineFileSystem): String? {
    if (!fileSystem.exists(this)) return null
    return fileSystem.readString(this)
}

private const val CodexAuthFileName: String = "auth.json"
private const val CodexModelsCacheFileName: String = "models_cache.json"
private const val CodexConfigFileName: String = "config.toml"
private const val KodexHooksFileName: String = "hooks.json"

private val CodexConfigToml: Toml = Toml {
    ignoreUnknownKeys = true
}

private val KodexHooksJson: Json = Json(OpenAiJsonCodec) {
    ignoreUnknownKeys = false
}
