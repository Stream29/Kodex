package io.github.stream29.kodex.utils.shellclient

import kotlinx.io.files.Path

/** The host's dynamically resolved shell used when no shell is requested. */
public expect val Shell.Companion.default: Shell

/**
 * Finds an installed executable for [type].
 *
 * Resolution follows the host's explicit preference, `PATH`, and platform
 * fallback locations. It does not change paths decoded by [Shell.Serializer].
 *
 * @param preferredPath `null` starts host discovery without an explicit preference.
 * @return `null` when this host has no matching executable.
 */
public expect fun Shell.Companion.resolve(
    type: ShellType,
    preferredPath: Path? = null,
): Shell?
