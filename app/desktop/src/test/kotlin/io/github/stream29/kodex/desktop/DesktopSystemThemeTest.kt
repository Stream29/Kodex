package io.github.stream29.kodex.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skiko.SystemTheme

public class DesktopSystemThemeTest {
    @Test
    public fun skikoResultTakesPriority(): Unit {
        assertTrue(
            detectDesktopSystemDarkTheme(
                skikoTheme = SystemTheme.DARK,
                osName = "Linux",
                commandOutput = { error("Fallback must not run") },
            ) == true,
        )
        assertFalse(
            detectDesktopSystemDarkTheme(
                skikoTheme = SystemTheme.LIGHT,
                osName = "Linux",
                commandOutput = { error("Fallback must not run") },
            ) == true,
        )
    }

    @Test
    public fun unknownSkikoThemeUsesGnomeColorScheme(): Unit {
        val result = detectDesktopSystemDarkTheme(
            skikoTheme = SystemTheme.UNKNOWN,
            osName = "Linux",
            environment = mapOf("XDG_CURRENT_DESKTOP" to "ubuntu:GNOME"),
            commandOutput = { command ->
                when (command.last()) {
                    "color-scheme" -> "'prefer-dark'"
                    "gtk-theme" -> "'Yaru-blue'"
                    else -> null
                }
            },
        )

        assertTrue(result == true)
    }

    @Test
    public fun linuxThemeFallsBackToGtkThemeName(): Unit {
        assertTrue(
            parseLinuxDarkTheme(
                colorScheme = "'default'",
                gtkTheme = "'Yaru-blue-dark'",
            ) == true,
        )
        assertFalse(
            parseLinuxDarkTheme(
                colorScheme = "'default'",
                gtkTheme = "'Yaru-blue'",
            ) == true,
        )
        assertNull(parseLinuxDarkTheme(colorScheme = null, gtkTheme = null))
    }

    @Test
    public fun windowsAppsThemeValueIsParsed(): Unit {
        assertTrue(
            parseWindowsAppsUseLightTheme(
                "AppsUseLightTheme    REG_DWORD    0x0",
            ) == true,
        )
        assertFalse(
            parseWindowsAppsUseLightTheme(
                "AppsUseLightTheme    REG_DWORD    0x1",
            ) == true,
        )
        assertNull(parseWindowsAppsUseLightTheme("missing"))
    }
}
