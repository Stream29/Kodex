package io.github.stream29.codex.lite.utils.hosttest

public actual fun environmentVariable(name: String): String? =
    js("process.env")[name] as? String
