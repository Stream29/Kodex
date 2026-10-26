package io.github.stream29.kodex.rpc.control

import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.backend.LocalBrowserGrant

/** Registers browser-only credentials for one exact origin/instance; returns the canonical directory. */
public expect suspend fun backendRegisterBrowser(endpoint: BackendEndpoint, grant: LocalBrowserGrant): String

/** Revokes admission and connected browser transports, not backend work. Never replaces an instance. */
public expect suspend fun backendRevokeBrowser(endpoint: BackendEndpoint, grant: LocalBrowserGrant)
