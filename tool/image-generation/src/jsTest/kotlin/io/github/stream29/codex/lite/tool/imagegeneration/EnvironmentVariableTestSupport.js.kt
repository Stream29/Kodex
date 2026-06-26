package io.github.stream29.codex.lite.tool.imagegeneration

internal actual fun environmentVariable(name: String): String? =
    js("process.env")[name] as? String
