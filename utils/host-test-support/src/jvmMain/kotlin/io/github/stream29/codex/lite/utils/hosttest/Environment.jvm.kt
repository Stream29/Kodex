package io.github.stream29.codex.lite.utils.hosttest

public actual fun environmentVariable(name: String): String? =
    System.getenv(name)
