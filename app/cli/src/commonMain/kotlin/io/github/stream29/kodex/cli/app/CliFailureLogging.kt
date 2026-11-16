package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.utils.logging.writeCliTerminalFailure
import io.github.stream29.kodex.utils.osenvironment.processId
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path

internal fun logCliFailure(failure: Throwable, home: Path) =
    runBlocking { writeCliTerminalFailure(home, processId(), failure) }
