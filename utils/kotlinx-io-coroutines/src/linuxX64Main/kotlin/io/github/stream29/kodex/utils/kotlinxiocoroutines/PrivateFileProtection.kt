@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.utils.kotlinxiocoroutines

import kotlinx.cinterop.toKString
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.chmod
import platform.posix.errno
import platform.posix.strerror

internal actual fun protectPrivateFile(path: Path) {
    if (chmod(path.toString(), (S_IRUSR or S_IWUSR).toUInt()) != 0) {
        throw IOException(
            "Protect private file failed for $path: ${strerror(errno)?.toKString()}",
        )
    }
}
