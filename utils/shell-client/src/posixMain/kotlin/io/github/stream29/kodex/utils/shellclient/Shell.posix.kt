package io.github.stream29.kodex.utils.shellclient

import kotlinx.io.files.Path
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

internal actual val shellHostPlatform: ShellHostPlatform
    @OptIn(ExperimentalNativeApi::class)
    get() =
        if (Platform.osFamily == OsFamily.MACOSX) {
            ShellHostPlatform.Macos
        } else {
            ShellHostPlatform.Linux
        }

internal fun Shell.invocation(
    command: String,
    login: Boolean,
): ShellInvocation =
    ShellInvocation(
        executable = path.toString(),
        argumentsBeforeCommand = type.argumentsBeforeCommand(login),
        command = command,
    )

internal fun Shell.invocation(
    command: String,
    workingDirectory: Path,
    login: Boolean,
): ShellInvocation =
    ShellInvocation(
        executable = path.toString(),
        argumentsBeforeCommand = type.argumentsBeforeCommand(login),
        command = when (type) {
            ShellType.Sh, ShellType.Bash, ShellType.Zsh ->
                "cd '${workingDirectory.toString().replace("'", "'\\\"'\\\"'")}' && $command"

            ShellType.PowerShell ->
                "Set-Location -LiteralPath '${workingDirectory.toString().replace("'", "''")}'; $command"

            ShellType.Cmd ->
                "cd /d \"${workingDirectory.toString().replace("\"", "\"\"")}\" && $command"
        },
    )
