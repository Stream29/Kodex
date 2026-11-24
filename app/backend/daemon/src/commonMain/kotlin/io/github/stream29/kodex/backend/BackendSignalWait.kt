package io.github.stream29.kodex.backend

/** Waits for an installed stop signal and drains the OS wait before returning or throwing. */
internal expect suspend fun awaitBackendStopSignal()
