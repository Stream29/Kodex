package io.github.stream29.kodex.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

/**
 * Tracks the platform appearance and falls back to desktop settings when Skiko cannot identify it.
 */
@Composable
internal fun rememberDesktopSystemDarkTheme(): Boolean {
    val composeFallback = isSystemInDarkTheme()
    val darkTheme = remember {
        MutableStateFlow(detectDesktopSystemDarkTheme() ?: composeFallback)
    }
    DisposableEffect(composeFallback, darkTheme) {
        darkTheme.value = detectDesktopSystemDarkTheme() ?: composeFallback
        val observer = DesktopSystemThemeObserver { isDark ->
            darkTheme.value = isDark
        }
        onDispose(observer::close)
    }
    val currentDarkTheme by darkTheme.collectAsState()
    return currentDarkTheme
}

internal fun detectDesktopSystemDarkTheme(
    skikoTheme: SystemTheme = currentSystemTheme,
    osName: String = System.getProperty("os.name").orEmpty(),
    environment: Map<String, String> = System.getenv(),
    commandOutput: (List<String>) -> String? = ::readCommandOutput,
): Boolean? = when (skikoTheme) {
    SystemTheme.DARK -> true
    SystemTheme.LIGHT -> false
    SystemTheme.UNKNOWN -> detectDesktopSystemDarkThemeFallback(
        osName = osName,
        environment = environment,
        commandOutput = commandOutput,
    )
}

private fun detectDesktopSystemDarkThemeFallback(
    osName: String,
    environment: Map<String, String>,
    commandOutput: (List<String>) -> String?,
): Boolean? {
    val normalizedOsName = osName.lowercase(Locale.ROOT)
    return when {
        "linux" in normalizedOsName -> {
            val desktop = environment["XDG_CURRENT_DESKTOP"].orEmpty().lowercase(Locale.ROOT)
            if ("kde" in desktop) {
                val colorScheme = commandOutput(
                    listOf("kreadconfig6", "--group", "General", "--key", "ColorScheme"),
                ) ?: commandOutput(
                    listOf("kreadconfig5", "--group", "General", "--key", "ColorScheme"),
                )
                parseNamedDesktopTheme(colorScheme)
            } else {
                parseLinuxDarkTheme(
                    colorScheme = commandOutput(
                        listOf(
                            "gsettings",
                            "get",
                            "org.gnome.desktop.interface",
                            "color-scheme",
                        ),
                    ),
                    gtkTheme = environment["GTK_THEME"]
                        ?.takeIf(String::isNotBlank)
                        ?: commandOutput(
                            listOf(
                                "gsettings",
                                "get",
                                "org.gnome.desktop.interface",
                                "gtk-theme",
                            ),
                        ),
                )
            }
        }

        "mac" in normalizedOsName -> {
            val interfaceStyle = commandOutput(
                listOf("defaults", "read", "-g", "AppleInterfaceStyle"),
            )
            interfaceStyle?.settingValue()?.equals("dark", ignoreCase = true) ?: false
        }

        "windows" in normalizedOsName -> parseWindowsAppsUseLightTheme(
            commandOutput(
                listOf(
                    "reg",
                    "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v",
                    "AppsUseLightTheme",
                ),
            ),
        )

        else -> null
    }
}

internal fun parseLinuxDarkTheme(
    colorScheme: String?,
    gtkTheme: String?,
): Boolean? {
    when (colorScheme.settingValue()?.lowercase(Locale.ROOT)) {
        "prefer-dark" -> return true
        "prefer-light" -> return false
    }
    return parseNamedDesktopTheme(gtkTheme)
}

internal fun parseWindowsAppsUseLightTheme(output: String?): Boolean? {
    val value = WindowsThemeValue.find(output.orEmpty())
        ?.groupValues
        ?.get(1)
        ?: return null
    val appsUseLightTheme = if (value.startsWith("0x", ignoreCase = true)) {
        value.drop(2).toLongOrNull(radix = 16)
    } else {
        value.toLongOrNull()
    } ?: return null
    return appsUseLightTheme == 0L
}

private fun parseNamedDesktopTheme(value: String?): Boolean? {
    val theme = value.settingValue()?.lowercase(Locale.ROOT) ?: return null
    return "dark" in theme || "black" in theme
}

private fun String?.settingValue(): String? = this
    ?.trim()
    ?.removeSurrounding("'")
    ?.removeSurrounding("\"")
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private fun readCommandOutput(command: List<String>): String? {
    if (command.isEmpty()) {
        return null
    }
    val process = runCatching {
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
    }.getOrNull() ?: return null
    return try {
        if (!process.waitFor(CommandTimeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else if (process.exitValue() != 0) {
            null
        } else {
            process.inputStream.bufferedReader().use { reader ->
                reader.readText().trim().takeIf(String::isNotEmpty)
            }
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } finally {
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }
}

private class DesktopSystemThemeObserver(
    private val detector: () -> Boolean? = ::detectDesktopSystemDarkTheme,
    private val onThemeChanged: (Boolean) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val monitorProcess = AtomicReference<Process?>()
    private var lastTheme: Boolean? = null
    private val worker = Thread(::observe, "kodex-system-theme").apply {
        isDaemon = true
        start()
    }

    private fun observe(): Unit {
        publishCurrentTheme()
        if (usesGSettings() && monitorGSettings()) {
            return
        }
        while (!closed.get()) {
            try {
                Thread.sleep(PollIntervalMillis)
            } catch (_: InterruptedException) {
                if (closed.get()) {
                    return
                }
            }
            publishCurrentTheme()
        }
    }

    private fun usesGSettings(): Boolean {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val desktop = System.getenv("XDG_CURRENT_DESKTOP").orEmpty().lowercase(Locale.ROOT)
        return "linux" in osName && "kde" !in desktop
    }

    private fun monitorGSettings(): Boolean {
        val process = runCatching {
            ProcessBuilder("gsettings", "monitor", "org.gnome.desktop.interface")
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return false
        monitorProcess.set(process)
        return try {
            process.inputStream.bufferedReader().use { reader ->
                while (!closed.get()) {
                    val update = reader.readLine() ?: break
                    if (update.isNotBlank()) {
                        publishCurrentTheme()
                    }
                }
            }
            closed.get()
        } catch (_: Exception) {
            closed.get()
        } finally {
            monitorProcess.compareAndSet(process, null)
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    private fun publishCurrentTheme(): Unit {
        val currentTheme = detector() ?: return
        if (currentTheme != lastTheme) {
            lastTheme = currentTheme
            onThemeChanged(currentTheme)
        }
    }

    override fun close(): Unit {
        if (closed.compareAndSet(false, true)) {
            monitorProcess.getAndSet(null)?.destroyForcibly()
            worker.interrupt()
        }
    }
}

private val WindowsThemeValue = Regex(
    pattern = """AppsUseLightTheme\s+REG_DWORD\s+(0x[0-9a-f]+|\d+)""",
    option = RegexOption.IGNORE_CASE,
)
private const val CommandTimeoutSeconds = 2L
private const val PollIntervalMillis = 2_000L
