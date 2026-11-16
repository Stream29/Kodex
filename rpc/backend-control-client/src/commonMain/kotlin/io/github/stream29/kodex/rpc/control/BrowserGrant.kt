package io.github.stream29.kodex.rpc.control

import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.backend.LocalBrowserGrant

public actual suspend fun backendRegisterBrowser(endpoint: BackendEndpoint, grant: LocalBrowserGrant): String =
    BackendControlClient().use { it.registerBrowser(endpoint, grant) }

public actual suspend fun backendRevokeBrowser(endpoint: BackendEndpoint, grant: LocalBrowserGrant): Unit =
    BackendControlClient().use { it.revokeBrowser(endpoint, grant) }
