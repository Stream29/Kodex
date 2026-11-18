@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.localserver

/**
 * Enables same-machine scoped browser grants. The host validates the requested directory.
 */
public expect class LocalBrowserAccess(
    resolveWorkingDirectory: suspend (String?) -> String,
)
