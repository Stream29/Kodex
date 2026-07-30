package io.github.stream29.kodex.utils.processclient

private val isWindows: Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

internal actual val interactiveProcessCommand: ProcessCommand =
    if (isWindows) {
        ProcessCommand(
            executable = "powershell.exe",
            arguments = listOf(
                "-NoProfile",
                "-Command",
                "\$line = [Console]::In.ReadLine(); " +
                    "[Console]::Out.WriteLine('out=' + \$line); " +
                    "[Console]::Error.WriteLine('err=' + \$line)",
            ),
        )
    } else {
        ProcessCommand(
            executable = "/bin/sh",
            arguments = listOf(
                "-c",
                "IFS= read -r line; printf 'out=%s\\n' \"\$line\"; printf 'err=%s\\n' \"\$line\" >&2",
            ),
        )
    }

internal actual val delayedProcessCommand: ProcessCommand =
    if (isWindows) {
        ProcessCommand(
            executable = "powershell.exe",
            arguments = listOf("-NoProfile", "-Command", "Start-Sleep -Seconds 30"),
        )
    } else {
        ProcessCommand(
            executable = "/bin/sh",
            arguments = listOf("-c", "sleep 30"),
        )
    }
