package io.github.stream29.codex.lite.utils.ktorclientext

import io.ktor.http.HttpHeaders

/** Codex CLI origin marker header used by OpenAI subscription endpoints. */
public val HttpHeaders.CodexOriginator: String
    get() = "originator"

/** ChatGPT account selector header used by OpenAI subscription endpoints. */
public val HttpHeaders.ChatGptAccountId: String
    get() = "ChatGPT-Account-ID"

/** Codex client version header used by the OpenAI search endpoint. */
public val HttpHeaders.OpenAiSearchVersion: String
    get() = "version"
