package io.github.stream29.codex.lite.openai.codexclistorage

import io.github.stream29.codex.lite.openai.OpenAiSubscriptionAuthState
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionPlan
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.codex.lite.utils.osenvironment.userHomeDirectory
import kotlinx.io.files.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * @param cause Nullable because storage failures may be detected locally; `null`
 * means there is no lower-level cause.
 */
public class CodexCliStorageException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

public class CodexCliStorage(
    public val directory: Path,
    private val fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
) {
    public val authPath: Path
        get() = Path(directory, CodexAuthFileName)

    public val modelsCachePath: Path
        get() = Path(directory, CodexModelsCacheFileName)

    public val configPath: Path
        get() = Path(directory, CodexConfigFileName)

    public suspend fun isCodexDirectory(): Boolean =
        fileSystem.exists(authPath)

    public suspend fun readAuth(): OpenAiSubscriptionAuthState {
        val text = readRequiredText(authPath, "Codex CLI auth.json")
        val auth = try {
            OpenAiJsonCodec.decodeFromString(CodexAuthJson.serializer(), text)
        } catch (error: Throwable) {
            throw CodexCliStorageException("Codex CLI auth.json must be valid JSON.", error)
        }

        val authMode = auth.authMode
        if (authMode != "chatgpt" && authMode != "chatgpt_auth_tokens") {
            throw CodexCliStorageException("Codex CLI auth.json must contain ChatGPT auth_mode.")
        }

        val tokens = auth.tokens
            ?: throw CodexCliStorageException("Codex CLI auth.json must contain tokens.")
        val accessToken = tokens.accessToken?.takeIf(String::isNotBlank)
            ?: throw CodexCliStorageException("Codex CLI auth.json must contain a non-empty access token.")

        return OpenAiSubscriptionAuthState(
            accessToken = accessToken,
            accountId = tokens.accountId?.takeIf(String::isNotBlank),
            planType = tokens.planType?.takeIf(String::isNotBlank)?.let(OpenAiSubscriptionPlan::fromRawValue),
        )
    }

    public suspend fun readModelsCache(): CodexModelsCache {
        val text = readRequiredText(modelsCachePath, "Codex CLI models_cache.json")
        return try {
            OpenAiJsonCodec.decodeFromString(CodexModelsCache.serializer(), text)
        } catch (error: Throwable) {
            throw CodexCliStorageException("Codex CLI models_cache.json must be valid JSON.", error)
        }
    }

    public suspend fun readConfigToml(): String =
        readRequiredText(configPath, "Codex CLI config.toml")

    private suspend fun readRequiredText(path: Path, label: String): String {
        if (!fileSystem.exists(path)) {
            throw CodexCliStorageException("$label was not found at $path.")
        }
        val text = try {
            fileSystem.readString(path)
        } catch (error: Throwable) {
            throw CodexCliStorageException("$label could not be read at $path.", error)
        }
        if (text.isBlank()) {
            throw CodexCliStorageException("$label must not be empty.")
        }
        return text
    }
}

public fun codexDirectory(userHome: Path): Path =
    Path(userHome, ".codex")

/**
 * @return Nullable because the host may not expose a readable home directory;
 * `null` means no default Codex directory path can be derived.
 */
public fun defaultCodexDirectory(): Path? =
    userHomeDirectory()?.let(::codexDirectory)

public suspend fun isCodexDirectory(
    path: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): Boolean =
    CodexCliStorage(path, fileSystem).isCodexDirectory()

/**
 * @return Nullable because the conventional `.codex` directory may be absent;
 * `null` means no Codex directory was found under `userHome`.
 */
public suspend fun detectCodexDirectory(
    userHome: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): Path? {
    val directory = codexDirectory(userHome)
    return directory.takeIf { isCodexDirectory(it, fileSystem) }
}

/**
 * @return Nullable because none of the candidate paths may exist as a Codex
 * directory; `null` means no candidate matched.
 */
public suspend fun detectCodexDirectory(
    candidates: Iterable<Path>,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): Path? {
    for (candidate in candidates) {
        if (isCodexDirectory(candidate, fileSystem)) return candidate
    }
    return null
}

/**
 * @return Nullable because the conventional home-based Codex directory may be
 * absent; `null` means no default Codex directory was found.
 */
public suspend fun detectDefaultCodexDirectory(
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): Path? {
    val directory = defaultCodexDirectory() ?: return null
    return directory.takeIf { isCodexDirectory(it, fileSystem) }
}

public suspend fun requireCodexDirectory(
    userHome: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): Path =
    detectCodexDirectory(userHome, fileSystem)
        ?: throw CodexCliStorageException("Codex directory was not found under $userHome.")

public suspend fun requireCodexDirectory(
    candidates: Iterable<Path>,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): Path =
    detectCodexDirectory(candidates, fileSystem)
        ?: throw CodexCliStorageException("Codex directory was not found in candidate paths.")

public suspend fun requireDefaultCodexDirectory(
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): Path =
    detectDefaultCodexDirectory(fileSystem)
        ?: throw CodexCliStorageException("Default Codex directory was not found.")

public suspend fun readCodexAuth(
    codexDirectory: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): OpenAiSubscriptionAuthState =
    CodexCliStorage(codexDirectory, fileSystem).readAuth()

private const val CodexAuthFileName: String = "auth.json"
private const val CodexModelsCacheFileName: String = "models_cache.json"
private const val CodexConfigFileName: String = "config.toml"

/**
 * @property clientVersion Nullable because older or partial Codex CLI cache
 * files may omit it; `null` means no cached client version is available.
 */
@Serializable
public data class CodexModelsCache(
    @SerialName("client_version")
    public val clientVersion: String? = null,
    public val models: List<CodexCachedModel> = emptyList(),
)

/**
 * @property slug Nullable because cache entries are decoded before validation;
 * `null` means no usable backend model slug was present.
 */
@Serializable
public data class CodexCachedModel(
    public val slug: String? = null,
)

/**
 * @property authMode Nullable because Codex CLI auth JSON may omit it; `null`
 * means `readAuth` has not accepted an auth mode.
 * @property tokens Nullable because Codex CLI auth JSON may omit the token
 * object; `null` means no token object was present for validation.
 */
@Serializable
private data class CodexAuthJson(
    @SerialName("auth_mode")
    val authMode: String? = null,
    val tokens: CodexAuthTokens? = null,
)

/**
 * @property accessToken Nullable because token files are decoded before
 * validation; `null` means no usable access token was present.
 * @property accountId Nullable because Codex CLI may omit the account id;
 * `null` means no account id should be propagated.
 * @property planType Nullable because Codex CLI may omit the plan type; `null`
 * means no plan type should be propagated.
 */
@Serializable
private data class CodexAuthTokens(
    @SerialName("access_token")
    val accessToken: String? = null,
    @SerialName("account_id")
    val accountId: String? = null,
    @SerialName("plan_type")
    val planType: String? = null,
)
