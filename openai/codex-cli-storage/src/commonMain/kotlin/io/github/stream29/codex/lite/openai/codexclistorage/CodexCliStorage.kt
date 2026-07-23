package io.github.stream29.codex.lite.openai.codexclistorage

import dev.eav.tomlkt.Toml
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

public class CodexCliStorage(
    internal val directory: Path,
    internal val fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
) {
    private val authPath: Path = Path(directory, CodexAuthFileName)
    private val modelsCachePath: Path = Path(directory, CodexModelsCacheFileName)
    private val configPath: Path = Path(directory, CodexConfigFileName)

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
}

private suspend fun Path.readTextOrNull(fileSystem: CoroutineFileSystem): String? {
    if (!fileSystem.exists(this)) return null
    return fileSystem.readString(this)
}

private const val CodexAuthFileName: String = "auth.json"
private const val CodexModelsCacheFileName: String = "models_cache.json"
private const val CodexConfigFileName: String = "config.toml"

private val CodexConfigToml: Toml = Toml {
    ignoreUnknownKeys = true
}
