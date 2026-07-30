package io.github.stream29.codex.lite.cli.auth

import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionTokens
import io.github.stream29.codex.lite.openai.codexclistorage.CodexAuthMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Private credentials owned by Codex Lite when global settings select that source. */
@Serializable
internal data class CodexLiteAuthFile(
    @SerialName("auth_mode")
    val authMode: CodexAuthMode,
    val tokens: OpenAiSubscriptionTokens,
    @SerialName("last_refresh")
    val lastRefresh: Instant,
)

internal val AuthYaml: Yaml = Yaml(
    configuration = YamlConfiguration(
        encodeDefaults = false,
        strictMode = false,
        singleLineStringStyle = SingleLineStringStyle.PlainExceptAmbiguous,
        breakScalarsAt = Int.MAX_VALUE,
    ),
)

internal const val CodexLiteAuthFileName: String = "auth.yml"
