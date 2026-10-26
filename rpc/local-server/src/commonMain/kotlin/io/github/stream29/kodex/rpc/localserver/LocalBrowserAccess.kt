@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.localserver

import io.github.stream29.kodex.backend.canonicalLocalBrowserOrigin

public actual class LocalBrowserAccess actual constructor(
    allowedOrigins: Set<String>,
    private val resolveWorkingDirectory: suspend (String?) -> String,
) {
    public actual val allowedOrigins: Set<String> = allowedOrigins.map(::canonicalLocalBrowserOrigin).toSet()
    internal suspend fun resolveDirectory(requested: String?): String = resolveWorkingDirectory(requested)
}
