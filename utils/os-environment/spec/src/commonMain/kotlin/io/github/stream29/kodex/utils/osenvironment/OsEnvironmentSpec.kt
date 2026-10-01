package io.github.stream29.kodex.utils.osenvironment

import kotlinx.io.files.Path

/**
 * Host information required by platform-independent Kodex services.
 *
 * The spec deliberately does not prescribe whether values come from JVM,
 * Node.js, or native platform APIs.
 */
public interface OsEnvironment {
    /** Returns an environment variable, or `null` when it is unset. */
    public fun environmentVariable(name: String): String?

    /** Returns the detected home directory, or `null` when unavailable. */
    public fun userHomeDirectory(): Path?

    /** Returns the current process identifier. */
    public fun processId(): Long
}
