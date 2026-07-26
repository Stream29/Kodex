package io.github.stream29.codex.lite.openai.codexclistorage

internal actual object CodexCliStoragePlatform {
    actual val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
