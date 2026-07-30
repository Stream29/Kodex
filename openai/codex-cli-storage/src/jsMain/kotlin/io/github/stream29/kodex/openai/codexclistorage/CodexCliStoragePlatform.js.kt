package io.github.stream29.kodex.openai.codexclistorage

import node.os.platform

internal actual object CodexCliStoragePlatform {
    actual val isWindows: Boolean = platform().toString() == "win32"
}
