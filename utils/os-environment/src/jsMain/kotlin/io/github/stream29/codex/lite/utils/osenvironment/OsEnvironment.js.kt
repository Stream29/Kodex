package io.github.stream29.codex.lite.utils.osenvironment

import kotlinx.io.files.Path

public actual fun environmentVariable(name: String): String? =
    js("process.env")[name] as? String

public actual fun userHomeDirectory(): Path? =
    userHomeDirectoryFromEnvironment()
