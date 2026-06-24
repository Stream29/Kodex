package io.github.stream29.codex.lite.codexcompatibility

import io.github.stream29.codex.lite.auth.OpenAiSubscriptionAuth
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * @param cause Nullable because compatibility failures may be detected locally;
 * `null` means there is no lower-level cause.
 */
public class CodexCompatibilityException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

public fun codexDirectory(userHome: Path): Path = Path(userHome, ".codex")

public fun isCodexDirectory(path: Path): Boolean =
    SystemFileSystem.exists(Path(path, "auth.json"))

/**
 * @return Nullable because the conventional `.codex` directory may be absent;
 * `null` means no Codex directory was found under `userHome`.
 */
public fun detectCodexDirectory(userHome: Path): Path? =
    codexDirectory(userHome).takeIf(::isCodexDirectory)

/**
 * @return Nullable because none of the candidate paths may exist as a Codex
 * directory; `null` means no candidate matched.
 */
public fun detectCodexDirectory(candidates: Iterable<Path>): Path? =
    candidates.firstOrNull(::isCodexDirectory)

public fun requireCodexDirectory(userHome: Path): Path =
    detectCodexDirectory(userHome)
        ?: throw CodexCompatibilityException("Codex directory was not found under $userHome.")

public fun requireCodexDirectory(candidates: Iterable<Path>): Path =
    detectCodexDirectory(candidates)
        ?: throw CodexCompatibilityException("Codex directory was not found in candidate paths.")

public fun readCodexAuth(codexDirectory: Path): OpenAiSubscriptionAuth {
    val authPath = Path(codexDirectory, "auth.json")
    val text = readRequiredText(authPath, "Codex CLI auth.json")
    val auth = try {
        codexJson.decodeFromString(CodexAuthJson.serializer(), text)
    } catch (error: Throwable) {
        throw CodexCompatibilityException("Codex CLI auth.json must be valid JSON.", error)
    }

    val authMode = auth.authMode
    if (authMode != "chatgpt" && authMode != "chatgpt_auth_tokens") {
        throw CodexCompatibilityException("Codex CLI auth.json must contain ChatGPT auth_mode.")
    }

    val tokens = auth.tokens
        ?: throw CodexCompatibilityException("Codex CLI auth.json must contain tokens.")
    val accessToken = tokens.accessToken?.takeIf(String::isNotBlank)
        ?: throw CodexCompatibilityException("Codex CLI auth.json must contain a non-empty access token.")

    return OpenAiSubscriptionAuth(
        accessToken = accessToken,
        accountId = tokens.accountId?.takeIf(String::isNotBlank),
        planType = tokens.planType?.takeIf(String::isNotBlank),
        isFedrampAccount = tokens.isFedrampAccount,
    )
}

private fun readRequiredText(path: Path, label: String): String {
    if (!SystemFileSystem.exists(path)) {
        throw CodexCompatibilityException("$label was not found at $path.")
    }
    val source = SystemFileSystem.source(path).buffered()
    val text = try {
        source.readString()
    } finally {
        source.close()
    }
    if (text.isBlank()) {
        throw CodexCompatibilityException("$label must not be empty.")
    }
    return text
}

@OptIn(ExperimentalSerializationApi::class)
private val codexJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    namingStrategy = JsonNamingStrategy.SnakeCase
}

/**
 * @property authMode Nullable because Codex CLI auth JSON may omit it; `null`
 * means `readCodexAuth` has not accepted an auth mode.
 * @property tokens Nullable because Codex CLI auth JSON may omit the token
 * object; `null` means no token object was present for validation.
 */
@Serializable
private data class CodexAuthJson(
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
    val accessToken: String? = null,
    val accountId: String? = null,
    val planType: String? = null,
    val isFedrampAccount: Boolean = false,
)
