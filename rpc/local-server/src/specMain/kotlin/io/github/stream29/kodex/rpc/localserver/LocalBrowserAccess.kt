@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.localserver

/**
 * Same-machine browser admission. Explicit origins permit manual record import;
 * Native-authenticated short-lived grants separately authorize exact origins.
 * The host validates the requested backend directory. No Home discovery or cloud policy.
 */
public expect class LocalBrowserAccess(
    allowedOrigins: Set<String>,
    resolveWorkingDirectory: suspend (String?) -> String,
) {
    public val allowedOrigins: Set<String>
}
