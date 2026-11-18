@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.localserver

public actual class LocalBrowserAccess actual constructor(
    private val resolveWorkingDirectory: suspend (String?) -> String,
) {
    internal suspend fun resolveDirectory(requested: String?): String = resolveWorkingDirectory(requested)
}
