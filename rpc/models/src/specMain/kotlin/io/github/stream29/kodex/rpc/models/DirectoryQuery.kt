package io.github.stream29.kodex.rpc.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Read-only path observation in the authenticated backend's environment.
 * Both strings use backend OS syntax, never browser Path parsing. [launchBaseDirectory] is the
 * captured absolute CLI/welcome directory, not the daemon's cwd and not a mutable Session cwd.
 * Resolve relative [path] against that base and expand current-user ~ using backend Home.
 * These observations neither change the connection's admitted initial directory nor grant access.
 */
@Serializable
public data class DirectoryQuery(
    public val path: String,
    public val launchBaseDirectory: String,
)

/** Safe classification only; no exception text, file contents or stack traces cross this boundary. */
@Serializable
public enum class DirectoryQueryFailure {
    InvalidPath, InvalidLaunchBase, HomeDirectoryUnavailable, NotDirectory, FileSystem, LimitExceeded,
}

/** Paths and display names are backend-produced; clients must not infer parents using their OS. */
@Serializable
public data class BackendChildDirectory(public val path: String, public val name: String)

@Serializable
public sealed interface DirectoryValidationResult {
    @Serializable
    @SerialName("directory")
    public data class Directory(public val path: String) : DirectoryValidationResult

    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val reason: DirectoryQueryFailure,
        public val parentDirectory: String? = null,
    ) : DirectoryValidationResult
}

/** Complete, nonrecursive listing; failure is never a successful partial list. */
@Serializable
public sealed interface DirectoryListResult {
    @Serializable
    @SerialName("directories")
    public data class Directories(
        public val directory: String,
        public val parentDirectory: String?,
        /** Directories only, ordered by lowercase name then full path; omit dangling links. */
        public val children: List<BackendChildDirectory>,
    ) : DirectoryListResult

    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val reason: DirectoryQueryFailure,
        public val parentDirectory: String? = null,
    ) : DirectoryListResult
}
