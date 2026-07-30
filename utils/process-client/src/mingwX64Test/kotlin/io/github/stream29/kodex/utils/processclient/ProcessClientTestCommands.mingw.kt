package io.github.stream29.kodex.utils.processclient

internal actual val interactiveProcessCommand: ProcessCommand = ProcessCommand(
    executable = "powershell.exe",
    arguments = listOf(
        "-NoProfile",
        "-Command",
        "\$line = [Console]::In.ReadLine(); " +
            "[Console]::Out.WriteLine('out=' + \$line); " +
            "[Console]::Error.WriteLine('err=' + \$line)",
    ),
)

internal actual val delayedProcessCommand: ProcessCommand = ProcessCommand(
    executable = "powershell.exe",
    arguments = listOf("-NoProfile", "-Command", "Start-Sleep -Seconds 30"),
)
