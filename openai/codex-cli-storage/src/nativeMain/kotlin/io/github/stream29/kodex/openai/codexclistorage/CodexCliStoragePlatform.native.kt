package io.github.stream29.kodex.openai.codexclistorage

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

@OptIn(ExperimentalNativeApi::class)
internal actual object CodexCliStoragePlatform {
    actual val isWindows: Boolean = Platform.osFamily == OsFamily.WINDOWS
}
