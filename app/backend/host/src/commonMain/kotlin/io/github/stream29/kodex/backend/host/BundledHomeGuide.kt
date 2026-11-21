@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.stream29.kodex.backend.host

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
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
    If an older Home is rejected, set `KODEX_HOME` to a different, empty directory. Leave the old
    Home intact and use its previous Kodex version when you need to access it.

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

/** Returns false if the current path belongs to the user and was preserved. */
internal suspend fun ensureBundledHomeGuide(
    home: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): Boolean {
    val maxKnownGuideBytes = currentGuide.size.toLong()
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
    if (metadata != null) {
        if (!metadata.isRegularFile || metadata.size > maxKnownGuideBytes ||
            fileSystem.resolve(target) != target
        ) return false
        return fileSystem.readBytes(target, maxKnownGuideBytes + 1).contentEquals(currentGuide)
    }
    if (fileSystem.list(directory).any { it.name == target.name }) return false

    val temporary = Path(directory, ".kodex-home-guide-${Uuid.random()}.tmp")
    try {
        fileSystem.writeBytes(temporary, currentGuide, mustCreate = true)
        // A user edit while preparing the temp file takes precedence.
        if (fileSystem.list(directory).any { it.name == target.name }) return false
        fileSystem.atomicMove(temporary, target)
        return true
    } finally {
        withContext(NonCancellable) { fileSystem.delete(temporary, mustExist = false) }
    }
}
