package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.backend.BackendEndpoint
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

/** Browser/window lifetime only. The caller owns signal installation; no backend Stop is sent. */
public expect suspend fun runLocalWebFrontend(
    url: String,
    window: Boolean,
    stopRequested: () -> Boolean,
): Int
