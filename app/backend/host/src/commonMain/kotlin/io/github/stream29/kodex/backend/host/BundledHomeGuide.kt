@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.stream29.kodex.backend.host

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.ktor.util.sha1
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.uuid.Uuid

private val currentGuide = """
    ---
    name: kodex-home
    description: Find the current Kodex Home and inspect settings, logs, attachments, and session data safely.
    ---

    # Kodex Home

    Use the current environment context to identify the session and its working directory. The
    default Home is `~/.kodex`; an explicit Backend target can use a different Home. Only the
    Backend prepares and owns the Home. Do not start a second Backend on the same Home or remove
    lock/lease files. Normal use holds a read lease; upgrades require an exclusive write lease.

    ## Session data

    - `kodex.db` is the authoritative SQLite database for sessions, Agents, commits, and schedules.
    - `sessions/` contains asynchronous, best-effort mirrors for inspection. They can be absent or
      behind SQLite. Numbered JSON, `latest.json`, and old `archive.mark` are not public write APIs.
    - Use the application's session controls or supported CLI operations for session changes. Check
      `kodex --help` and the relevant subcommand's help; do not infer a write command from the
      database schema or edit the database or mirrors behind a running Backend.

    ## Other files

    - `settings.yml` holds global configuration. `auth.yml` holds private Kodex credentials; do not
      print, copy, or include credentials in diagnostics.
    - `log/` contains process logs; `generated_images/` holds generated image artifacts. Locate
      attachments using their recorded paths. Keep user files, `AGENTS.md`, and other skills intact.
    - Use `kodex backend status` for the current Backend state. Follow explicit instructions before
      stopping, reloading, or editing any settings. `version.json` is the migration checkpoint,
      not the last application version that used the Home.
""".trimIndent().plus("\n").encodeToByteArray()

// Hashes of the exact UTF-8 contents installed by the frozen 0.3.5 and 0.4.3 migrations.
// They match fixed product copies, not arbitrary files that happen to have the same size.
private val legacyGuideDigests = mapOf(
    2776 to "14af41aa4b377a0a58f8c328aa06a05ab5a52dc0",
    3484 to "6929c86dc94ed1dae984494b935bd7e3c3140adb",
)
private val maxKnownGuideBytes = maxOf(currentGuide.size, legacyGuideDigests.keys.max()).toLong()

/** Returns false if the current path belongs to the user and was preserved. */
internal suspend fun ensureBundledHomeGuide(
    home: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): Boolean {
    var directory = fileSystem.resolve(home)
    for (name in listOf("skills", "kodex-home")) {
        val child = Path(directory, name)
        val metadata = fileSystem.metadataOrNull(child)
        when {
            metadata == null -> {
                // Also preserve dangling links, which metadata on some hosts treats as missing.
                if (fileSystem.list(directory).any { it.name == name }) return false
                fileSystem.createDirectories(child)
            }
            !metadata.isDirectory || fileSystem.resolve(child) != child -> return false
        }
        directory = child
    }

    val target = Path(directory, "SKILL.md")
    val metadata = fileSystem.metadataOrNull(target)
    val previous = if (metadata == null) {
        if (fileSystem.list(directory).any { it.name == target.name }) return false
        null
    } else {
        if (!metadata.isRegularFile || metadata.size > maxKnownGuideBytes ||
            fileSystem.resolve(target) != target
        ) return false
        val bytes = fileSystem.readBytes(target, maxKnownGuideBytes + 1)
        if (bytes.contentEquals(currentGuide)) return true
        val expected = legacyGuideDigests[bytes.size] ?: return false
        if (sha1(bytes).toHexString() != expected) return false
        bytes
    }

    val temporary = Path(directory, ".kodex-home-guide-${Uuid.random()}.tmp")
    try {
        fileSystem.writeBytes(temporary, currentGuide, mustCreate = true)
        // A user edit while preparing the temp file takes precedence.
        if (previous == null) {
            if (fileSystem.list(directory).any { it.name == target.name }) return false
        } else {
            if (fileSystem.metadataOrNull(target)?.isRegularFile != true ||
                fileSystem.resolve(target) != target ||
                !fileSystem.readBytes(target, maxKnownGuideBytes + 1).contentEquals(previous)
            ) return false
        }
        fileSystem.atomicMove(temporary, target)
        return true
    } finally {
        withContext(NonCancellable) { fileSystem.delete(temporary, mustExist = false) }
    }
}
