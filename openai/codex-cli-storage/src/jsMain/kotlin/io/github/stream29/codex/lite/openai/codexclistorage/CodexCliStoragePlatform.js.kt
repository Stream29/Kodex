package io.github.stream29.codex.lite.openai.codexclistorage

import node.os.platform

internal actual object CodexCliStoragePlatform {
    actual val isWindows: Boolean = platform().toString() == "win32"
}
