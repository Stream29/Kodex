@file:Suppress("UnsafeCastFromDynamic")

package io.github.stream29.kodex.utils.processclient

private val nodeExecutable: String = js("process.execPath")

internal actual val interactiveProcessCommand: ProcessCommand = ProcessCommand(
    executable = nodeExecutable,
    arguments = listOf(
        "-e",
        """
        let input = "";
        process.stdin.setEncoding("utf8");
        process.stdin.on("data", chunk => input += chunk);
        process.stdin.on("end", () => {
            const line = input.replace(/\r?\n$/, "");
            process.stdout.write("out=" + line + "\n");
            process.stderr.write("err=" + line + "\n");
        });
        """.trimIndent(),
    ),
)

internal actual val delayedProcessCommand: ProcessCommand = ProcessCommand(
    executable = nodeExecutable,
    arguments = listOf("-e", "setTimeout(() => {}, 30000);"),
)

internal actual val environmentProcessCommand: ProcessCommand = ProcessCommand(
    executable = nodeExecutable,
    arguments = listOf("-e", "process.stdout.write(process.env.$TestEnvironmentName ?? '');"),
    environment = mapOf(TestEnvironmentName to TestEnvironmentValue),
)
