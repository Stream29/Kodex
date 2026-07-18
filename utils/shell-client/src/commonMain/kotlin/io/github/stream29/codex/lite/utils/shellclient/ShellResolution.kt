package io.github.stream29.codex.lite.utils.shellclient

import io.github.stream29.codex.lite.utils.osenvironment.environmentVariable
import kotlinx.io.files.Path

internal fun resolveDefaultShell(): Shell =
    when (shellHostPlatform) {
        ShellHostPlatform.Windows ->
            Shell.resolve(ShellType.PowerShell) ?: ultimateFallbackShell()

        ShellHostPlatform.Macos ->
            resolveUserShell()
                ?: Shell.resolve(ShellType.Zsh)
                ?: Shell.resolve(ShellType.Bash)
                ?: ultimateFallbackShell()

        ShellHostPlatform.Linux ->
            resolveUserShell()
                ?: Shell.resolve(ShellType.Bash)
                ?: Shell.resolve(ShellType.Zsh)
                ?: ultimateFallbackShell()
    }

internal fun resolveShell(
    type: ShellType,
    preferredPath: Path?,
): Shell? =
    resolveShellPath(type, preferredPath)?.let { path ->
        Shell(type = type, path = path)
    }

private fun resolveUserShell(): Shell? {
    val userShellPath = environmentVariable("SHELL")
        ?.takeIf(String::isNotBlank)
        ?: return null
    val type = userShellPath.shellTypeOrNull() ?: return null
    return Shell.resolve(type)
}

private fun resolveShellPath(
    type: ShellType,
    preferredPath: Path?,
): Path? =
    when (type) {
        ShellType.Zsh -> resolveBinary(
            type = type,
            preferredPath = preferredPath,
            binaryName = "zsh",
            fallbackPaths = listOf(Path("/bin/zsh")),
        )

        ShellType.Bash -> resolveBinary(
            type = type,
            preferredPath = preferredPath,
            binaryName = "bash",
            fallbackPaths = listOf(Path("/bin/bash"), Path("/usr/bin/bash")),
        )

        ShellType.Sh -> resolveBinary(
            type = type,
            preferredPath = preferredPath,
            binaryName = "sh",
            fallbackPaths = listOf(Path("/bin/sh")),
        )

        ShellType.PowerShell -> resolveBinary(
            type = type,
            preferredPath = preferredPath,
            binaryName = "pwsh",
            fallbackPaths = powerShellCoreFallbackPaths(),
        ) ?: resolveBinary(
            type = type,
            preferredPath = preferredPath,
            binaryName = "powershell",
            fallbackPaths = legacyPowerShellFallbackPaths(),
        )

        ShellType.Cmd -> resolveBinary(
            type = type,
            preferredPath = preferredPath,
            binaryName = "cmd",
            fallbackPaths = emptyList(),
        )
    }

private fun resolveBinary(
    type: ShellType,
    preferredPath: Path?,
    binaryName: String,
    fallbackPaths: List<Path>,
): Path? =
    preferredPath.existingRegularFileOrNull()
        ?: userShellPathFor(type).existingRegularFileOrNull()
        ?: platformShellPathFor(type).existingRegularFileOrNull()
        ?: findExecutableOnPath(binaryName)
        ?: fallbackPaths.firstNotNullOfOrNull(Path::existingRegularFileOrNull)

private fun userShellPathFor(type: ShellType): Path? {
    val shellPath = environmentVariable("SHELL")
        ?.takeIf(String::isNotBlank)
        ?: return null
    return shellPath.takeIf { it.shellTypeOrNull() == type }?.let(::Path)
}

private fun platformShellPathFor(type: ShellType): Path? =
    when (type) {
        ShellType.Cmd -> environmentVariable("COMSPEC")?.takeIf(String::isNotBlank)?.let(::Path)
        else -> null
    }

private fun findExecutableOnPath(binaryName: String): Path? {
    val pathEntries = environmentVariable("PATH")
        ?.split(pathSearchSeparator())
        .orEmpty()
    val candidateNames = executableCandidateNames(binaryName)
    return pathEntries.firstNotNullOfOrNull { directory ->
        candidateNames.firstNotNullOfOrNull { name ->
            val path = if (directory.isEmpty()) Path(name) else Path(directory, name)
            path.existingRegularFileOrNull()
        }
    }
}

private fun executableCandidateNames(binaryName: String): List<String> {
    if (shellHostPlatform != ShellHostPlatform.Windows || binaryName.hasExtension()) {
        return listOf(binaryName)
    }
    val extensions = environmentVariable("PATHEXT")
        ?.split(';')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.map { extension -> if (extension.startsWith('.')) extension else ".$extension" }
        .orEmpty()
        .ifEmpty { WindowsExecutableExtensions }
    return buildList {
        add(binaryName)
        extensions.forEach { extension -> add(binaryName + extension) }
    }
}

private fun String.hasExtension(): Boolean =
    substringAfterLast('/').substringAfterLast('\\').contains('.')

private fun pathSearchSeparator(): Char =
    if (shellHostPlatform == ShellHostPlatform.Windows) ';' else ':'

private fun powerShellCoreFallbackPaths(): List<Path> =
    if (shellHostPlatform == ShellHostPlatform.Windows) {
        buildList {
            windowsProgramFilesDirectories().forEach { directory ->
                add(Path(directory, "PowerShell", "7", "pwsh.exe"))
            }
            add(Path("C:\\Program Files\\PowerShell\\7\\pwsh.exe"))
        }
    } else {
        listOf(Path("/usr/local/bin/pwsh"))
    }

private fun legacyPowerShellFallbackPaths(): List<Path> =
    if (shellHostPlatform == ShellHostPlatform.Windows) {
        buildList {
            windowsSystemRoot()?.let { root ->
                add(Path(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe"))
            }
            add(Path("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"))
        }
    } else {
        emptyList()
    }

private fun windowsProgramFilesDirectories(): List<Path> =
    listOf("ProgramW6432", "ProgramFiles")
        .mapNotNull { variable -> environmentVariable(variable)?.takeIf(String::isNotBlank) }
        .distinct()
        .map(::Path)

private fun windowsSystemRoot(): Path? =
    sequenceOf("SystemRoot", "WINDIR")
        .mapNotNull { variable -> environmentVariable(variable)?.takeIf(String::isNotBlank) }
        .firstOrNull()
        ?.let(::Path)

private fun Path?.existingRegularFileOrNull(): Path? =
    this?.takeIf { path -> path.isRegularFileForShellResolution() }

private fun ultimateFallbackShell(): Shell =
    when (shellHostPlatform) {
        ShellHostPlatform.Windows -> Shell(type = ShellType.Cmd, path = Path("cmd.exe"))
        ShellHostPlatform.Macos, ShellHostPlatform.Linux -> Shell(type = ShellType.Sh, path = Path("/bin/sh"))
    }

private val WindowsExecutableExtensions: List<String> =
    listOf(".COM", ".EXE", ".BAT", ".CMD")
