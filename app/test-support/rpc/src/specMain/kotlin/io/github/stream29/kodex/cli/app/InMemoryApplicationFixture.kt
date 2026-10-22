package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.migration.KodexHomeHandle
import io.github.stream29.kodex.openai.client.OpenAiClient
import io.github.stream29.kodex.openai.client.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient as ModelClient
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient as LoginClient
import io.github.stream29.kodex.utils.osenvironment.requireUserHomeDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path

/** A test fixture, not another production backend bootstrap or a selectable deployment mode. */
public expect suspend fun <R> withKodexApplication(
    homeHandle: KodexHomeHandle,
    workingDirectory: Path = Path("."),
    codexDirectory: Path = Path(requireUserHomeDirectory(), ".codex"),
    agentsDirectory: Path = Path(requireUserHomeDirectory(), ".agents"),
    applicationWidth: Int? = null,
    createClient: (OpenAiAuthStore) -> ModelClient = { OpenAiClient(it) },
    createLoginClient: () -> LoginClient = { OpenAiLoginClient() },
    onPhase: suspend (ApplicationStartupPhase) -> Unit = {},
    render: suspend CoroutineScope.(KodexApplication) -> R,
): R
