package io.github.stream29.kodex.utils.processclient

internal actual val interactiveProcessCommand: ProcessCommand = ProcessCommand(
    executable = "/bin/sh",
    arguments = listOf(
        "-c",
        "IFS= read -r line; printf 'out=%s\\n' \"\$line\"; printf 'err=%s\\n' \"\$line\" >&2",
    ),
)

internal actual val delayedProcessCommand: ProcessCommand = ProcessCommand(
    executable = "/bin/sh",
    arguments = listOf("-c", "sleep 30"),
)

internal actual val environmentProcessCommand: ProcessCommand = ProcessCommand(
    executable = "/bin/sh",
    arguments = listOf("-c", "printf %s \"\$$TestEnvironmentName\""),
    environment = mapOf(TestEnvironmentName to TestEnvironmentValue),
)
