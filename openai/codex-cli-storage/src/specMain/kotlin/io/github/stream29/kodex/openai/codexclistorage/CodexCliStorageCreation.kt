@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.openai.codexclistorage

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

public expect class CodexCliStorageImpl : CodexCliStorage {
    /**
     * @return Nullable because Codex CLI may not be signed in; `null` means
     * `auth.json` is absent.
     */
    override suspend fun readAuthOrNull(): CodexAuthJson?

    /**
     * Reads and classifies every Codex MCP declaration for an explicit import.
     *
     * A declaration is supported only when all of its fields can be preserved
     * by Kodex. Unsupported previews expose field names, never their values.
     */
    override suspend fun readMcpImportCandidates(): List<CodexCliMcpImportCandidate>
}

/** Creates a read-only Codex CLI storage view over [directory]. */
public expect fun CodexCliStorage(
    directory: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): CodexCliStorage
