package io.github.stream29.kodex.cli.app

import kotlinx.io.files.Path

/** Runs and closes only the terminal renderer; the caller owns the frontend Application. */
public expect suspend fun runTuiApplication(application: KodexApplication): Unit

/** Native diagnostics with an injected process-liveness probe; never initializes backend logging. */
public expect suspend fun initializeTuiLogging(home: Path, processAlive: (Long) -> Boolean): Unit
