package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.backend.LocalFrontendPairingOffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.Source

/**
 * Scoped static/bootstrap listener and browser admission. Resource reader and secure RNG are
 * supplied by the Native composition root. Closing it revokes only this admission, not backend work.
 */
public expect suspend fun <R> withLocalFrontendHost(
    endpoint: BackendEndpoint,
    directory: String,
    openResource: (String) -> Source?,
    newSecret: () -> String,
    block: suspend CoroutineScope.(privateLaunchUrl: String) -> R,
): R

/** Foreground scoped companion for an explicitly trusted static loopback Origin. */
public expect suspend fun <R> withLocalFrontendPairingHost(
    endpoint: BackendEndpoint,
    directory: String,
    origin: String,
    newSecret: () -> String,
    block: suspend CoroutineScope.(LocalFrontendPairingOffer) -> R,
): R

/** Available local application window engines; Chrome is supported only on Linux. */
public enum class LocalAppEngine { Default, Chrome, System }

/** Resolves the default or rejects an engine unavailable on this host before launching resources. */
public expect fun resolveLocalAppEngine(requested: LocalAppEngine): LocalAppEngine

/** Browser/window lifetime only. The caller owns signal installation; no backend Stop is sent. */
public expect suspend fun runLocalWebFrontend(
    url: String,
    window: Boolean,
    stopRequested: () -> Boolean,
    engine: LocalAppEngine = LocalAppEngine.Default,
): Int
