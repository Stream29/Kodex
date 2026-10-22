@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.auth

import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokens
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path
import kotlin.time.Clock

/**
 * Backend-only authority for the two existing file formats. Source selection
 * is observed, never rewritten by login/remove. The injected OAuth transport
 * belongs to the enclosing backend and is not closed by this store.
 */
public expect class BackendFileSystemAuthStore : OpenAiAuthStore, AutoCloseable {
    override val state: StateFlow<OpenAiAuthState>

    /** Internal preparation fence, not a persistent identity or wire handle. */
    public suspend fun beginLogin(source: KodexAuthSource): Long

    /** Saves only the bound source and rejects a login invalidated by remove/new login. */
    public suspend fun commitLogin(source: KodexAuthSource, generation: Long, tokens: OpenAiSubscriptionTokens)
    public suspend fun remove(source: KodexAuthSource)

    /** Internal maintenance; no manual reload RPC or frontend polling. */
    public suspend fun reload(source: KodexAuthSource)
    override fun close()
}

/** Creates the two-source authority in a child of the backend lifecycle. */
public expect suspend fun CoroutineScope.BackendFileSystemAuthStore(
    dataDirectory: Path,
    codexHome: Path,
    selectedSource: StateFlow<KodexAuthSource>,
    loginClient: OpenAiLoginClient,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    clock: Clock = Clock.System,
): BackendFileSystemAuthStore
