package io.github.stream29.kodex.utils.shellclient

import kotlinx.io.files.Path

/** Synchronous executable-path check used while resolving a host shell. */
internal expect fun Path.isRegularFileForShellResolution(): Boolean
